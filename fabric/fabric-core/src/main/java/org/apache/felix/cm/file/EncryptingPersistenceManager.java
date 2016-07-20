/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package org.apache.felix.cm.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;

import org.jasypt.encryption.pbe.PBEStringEncryptor;
import org.jasypt.exceptions.EncryptionOperationNotPossibleException;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encrypting/decrypting version of {@link FilePersistenceManager}
 * TODO: doesn't work under security manager (yet)
 */
public class EncryptingPersistenceManager extends FilePersistenceManager {

    public static Logger LOG = LoggerFactory.getLogger(EncryptingPersistenceManager.class);

    private static final String TMP_EXT = ".tmp";
    private static final String FILE_EXT = ".config";
    private final PBEStringEncryptor encryptor;

    public EncryptingPersistenceManager(BundleContext bundleContext, String location, PBEStringEncryptor encryptor) {
        super(bundleContext, location);
        this.encryptor = encryptor;
    }

    // unfortunately we have to copy&paste some of the private methods

    @Override
    public Dictionary load(String pid) throws IOException {
        final File cfgFile = getFile(pid);

        if (System.getSecurityManager() != null) {
            return _privilegedLoad(cfgFile);
        }

        return _load(cfgFile);
    }

    private Dictionary _privilegedLoad(final File cfgFile) throws IOException {
        try {
            Object result = AccessController.doPrivileged(new PrivilegedExceptionAction() {
                public Object run() throws IOException {
                    return _load(cfgFile);
                }
            });

            return (Dictionary) result;
        } catch (PrivilegedActionException pae) {
            // FELIX-2771: getCause() is not available in Foundation
            throw (IOException) pae.getException();
        }
    }

    @Override
    Dictionary _load(File cfgFile) throws IOException {
        // this method is not part of the API of this class but is made
        // package private to prevent the creation of a synthetic method
        // for use by the DictionaryEnumeration._seek method

        // synchronize this instance to make at least sure, the file is
        // not at the same time accessed by another thread (see store())
        // we have to synchronize the complete load time as the store
        // method might want to replace the file while we are reading and
        // still have the file open. This might be a problem e.g. in Windows
        // environments, where files may not be removed which are still open
        synchronized (this) {
            InputStream ins = null;
            try {
                ins = new FileInputStream(cfgFile);
                Dictionary<String, String> storedProps = ConfigurationHandler.read(ins);
                // we can assume that there's fabric.zookeeper.encrypted.values property containing
                // encrypted properties
                if (storedProps.get("fabric.zookeeper.encrypted.values") != null) {
                    String encryptedValuesList = storedProps.get("fabric.zookeeper.encrypted.values");
                    String[] encryptedValues = encryptedValuesList.split("\\s*,\\s");
                    for (String encryptedValue : encryptedValues) {
                        String value = storedProps.get(encryptedValue);
                        if (value != null && value.startsWith("crypt:")) {
                            storedProps.put(encryptedValue + ".encrypted", value);
                            try {
                                storedProps.put(encryptedValue, encryptor.decrypt(value.substring("crypt:".length())));
                            } catch (EncryptionOperationNotPossibleException e) {
                                LOG.error(e.getMessage(), e);
                            }
                        }
                    }
                }
                return storedProps;
            } finally {
                if (ins != null) {
                    try {
                        ins.close();
                    } catch (IOException ioe) {
                        // ignore
                    }
                }
            }
        }
    }

    @Override
    public void store(final String pid, final Dictionary props) throws IOException {
        if (System.getSecurityManager() != null) {
            _privilegedStore(pid, props);
        } else {
            _store(pid, props);
        }
    }

    private void _privilegedStore(final String pid, final Dictionary props) throws IOException {
        try {
            AccessController.doPrivileged(new PrivilegedExceptionAction() {
                public Object run() throws IOException {
                    _store(pid, props);
                    return null;
                }
            });
        } catch (PrivilegedActionException pae) {
            // FELIX-2771: getCause() is not available in Foundation
            throw (IOException) pae.getException();
        }
    }

    private void _store(final String pid, final Dictionary props) throws IOException {
        OutputStream out = null;
        File tmpFile = null;
        try {
            File cfgFile = new File(getLocation(), encodePid(pid) + FILE_EXT);
            Dictionary storedProps = props;
            if (props != null && props.get("fabric.zookeeper.encrypted.values") != null) {
                // ENTESB-5392: remove decrypted properties, so they're not stored in configadmin
                String encryptedValuesList = (String) props.get("fabric.zookeeper.encrypted.values");
                Hashtable<Object, Object> newProps = new Hashtable<>();
                for (Enumeration<?> e = props.keys(); e.hasMoreElements(); ) {
                    Object k = e.nextElement();
                    newProps.put(k, props.get(k));
                }

                String[] encryptedValues = encryptedValuesList.split("\\s*,\\s");
                for (String encryptedValue : encryptedValues) {
                    newProps.put(encryptedValue, props.get(encryptedValue + ".encrypted"));
                    newProps.remove(encryptedValue + ".encrypted");
                }
                storedProps = newProps;
            }

            // ensure parent path
            File cfgDir = cfgFile.getParentFile();
            cfgDir.mkdirs();

            // write the configuration to a temporary file
            tmpFile = File.createTempFile(cfgFile.getName(), TMP_EXT, cfgDir);
            out = new FileOutputStream(tmpFile);
            ConfigurationHandler.write(out, storedProps);
            out.close();

            // after writing the file, rename it but ensure, that no other
            // might at the same time open the new file
            // see load(File)
            synchronized (this) {
                // make sure the cfg file does not exists (just for sanity)
                if (cfgFile.exists()) {
                    // FELIX-4165: detect failure to delete old file
                    if (!cfgFile.delete()) {
                        throw new IOException("Cannot remove old file '" + cfgFile + "'; changes in '" + tmpFile
                                + "' cannot be persisted at this time");
                    }
                }

                // rename the temporary file to the new file
                if (!tmpFile.renameTo(cfgFile)) {
                    throw new IOException("Failed to rename configuration file from '" + tmpFile + "' to '" + cfgFile);
                }
            }
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ioe) {
                    // ignore
                }
            }

            if (tmpFile != null && tmpFile.exists()) {
                tmpFile.delete();
            }
        }
    }

}
