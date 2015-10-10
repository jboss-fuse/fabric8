/**
 *  Copyright 2005-2015 Red Hat, Inc.
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
package io.fabric8.patch.management.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import io.fabric8.patch.management.BackupService;
import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.patch.management.Utils;
import org.apache.commons.io.FileUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

public class FileBackupService implements BackupService {

    private final BundleContext systemContext;

    /**
     * Pass {@link BundleContext} of system bundle here.
     * @param systemContext
     */
    public FileBackupService(BundleContext systemContext) {
        this.systemContext = systemContext;
    }

    /**
     * Invoked just before Framework is restarted and data/cache directory is removed. We copy existing data
     * directories for current bundles and record for which bundle$$version it is used.
     * @param result used to create backup directories.
     * @throws IOException
     */
    @Override
    public void backupDataFiles(PatchResult result) throws IOException {
        Map<String, Bundle> bundlesWithData = new HashMap<>();
        // bundle.getDataFile("xxx") creates data dir if it didn't exist - it's not what we want
        String storageLocation = systemContext.getProperty("org.osgi.framework.storage");
        if (storageLocation == null) {
            System.out.println("Can't determine \"org.osgi.framework.storage\" property value");
            System.out.flush();
            return;
        }
        File cacheDir = new File(storageLocation);
        if (!cacheDir.isDirectory()) {
            return;
        }

        for (Bundle b : systemContext.getBundles()) {
            String sn = Utils.stripSymbolicName(b.getSymbolicName());
            if ("org.apache.karaf.features.core".equals(sn)) {
                // we don't want to preserve data directory of this bundle, because during rollup patch
                // we start with fresh features service state
                continue;
            }
            File dataDir = new File(cacheDir, "bundle" + b.getBundleId() + "/data");
            if (dataDir.isDirectory()) {
                String key = String.format("%s$$%s", sn, b.getVersion().toString());
                bundlesWithData.put(key, b);
            }
        }

        // this property file will be used to map full symbolicName$$version to a location where bundle data
        // is stored - the data must be restored both during R patch installation and rollback
        Properties properties = new Properties();

        File dataBackupDir = new File(result.getPatchData().getPatchLocation(),
                result.getPatchData().getId() + ".datafiles");
        for (BundleUpdate update : result.getBundleUpdates()) {
            // same update for both updated and reinstalled bundle
            String key = String.format("%s$$%s", update.getSymbolicName(), update.getPreviousVersion());
            if (bundlesWithData.containsKey(key)) {
                File dataFileBackupDir = new File(dataBackupDir, key + "/data");
                dataFileBackupDir.mkdirs();
                Bundle b = bundlesWithData.get(key);
                FileUtils.copyDirectory(b.getDataFile(""), dataFileBackupDir);
                properties.setProperty(key, key);
                if (update.getNewVersion() != null) {
                    properties.setProperty(String.format("%s$$%s", update.getSymbolicName(), update.getNewVersion()), key);
                }
            }
        }
        FileOutputStream propsFile = new FileOutputStream(new File(dataBackupDir, "backup.properties"));
        properties.store(propsFile, "Data files to restore after \"" + result.getPatchData().getId() + "\" installation");
        propsFile.close();
    }

}
