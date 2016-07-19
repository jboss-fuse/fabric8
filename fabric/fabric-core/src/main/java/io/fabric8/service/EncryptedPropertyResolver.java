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
package io.fabric8.service;

import java.lang.reflect.Field;
import java.util.Map;

import io.fabric8.api.FabricException;
import io.fabric8.api.FabricService;
import io.fabric8.api.PlaceholderResolver;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.utils.PasswordEncoder;
import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.cm.PersistenceManager;
import org.apache.felix.cm.file.EncryptingPersistenceManager;
import org.apache.felix.cm.impl.ConfigurationManager;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.jasypt.encryption.pbe.PBEStringEncryptor;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.fabric8.zookeeper.ZkPath.AUTHENTICATION_CRYPT_ALGORITHM;
import static io.fabric8.zookeeper.ZkPath.AUTHENTICATION_CRYPT_PASSWORD;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getStringData;

@ThreadSafe
@Component(name = "io.fabric8.placholder.resolver.crypt", label = "Fabric8 Encrypted Property Placeholder Resolver", metatype = false)
@Service({ PlaceholderResolver.class, EncryptedPropertyResolver.class })
@Properties({ @Property(name = "scheme", value = EncryptedPropertyResolver.RESOLVER_SCHEME) })
public final class EncryptedPropertyResolver extends AbstractComponent implements PlaceholderResolver {

    public static Logger LOG = LoggerFactory.getLogger(EncryptedPropertyResolver.class);
    public static final String RESOLVER_SCHEME = "crypt";

    private BundleContext bundleContext;
    private PBEStringEncryptor encryptor;
    private ServiceRegistration<PBEStringEncryptor> seRegistration;
//    private ServiceRegistration<?> pmRegistration;
    private PersistenceManager encryptingPersistenceManager;
    private PersistenceManager originalPersistenceManager;

    @Reference
    private ConfigurationAdmin configAdmin;

    @Activate
    void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
//        if (pmRegistration != null) {
//            pmRegistration.unregister();
//        }
        if (originalPersistenceManager != null) {
            inject(configAdmin, originalPersistenceManager);
        }
        if (seRegistration != null) {
            seRegistration.unregister();
        }
        encryptor = null;
    }

    /**
     * When {@link FabricService} becomes available, we can initialize this {@link PlaceholderResolver}
     * @param fabricService
     */
    public void initialize(FabricService fabricService) {
        encryptor = getEncryptor(fabricService);
        if (bundleContext != null) {
            seRegistration = bundleContext.registerService(PBEStringEncryptor.class, encryptor, null);
            BundleContext context = FrameworkUtil.getBundle(PersistenceManager.class).getBundleContext();
            encryptingPersistenceManager = new EncryptingPersistenceManager(
                    context, context.getProperty(ConfigurationManager.CM_CONFIG_DIR),
                    encryptor);
//            Hashtable<String, Object> props = new Hashtable<>();
//            props.put(Constants.SERVICE_PID, fpm.getClass().getName());
//            props.put(Constants.SERVICE_DESCRIPTION, "Encrypting Filesystem Persistence Manager");
//            props.put(Constants.SERVICE_RANKING, Integer.MAX_VALUE);
//            props.put("encrypting", "true");
//            pmRegistration = bundleContext.registerService(PersistenceManager.class, fpm, props);
            originalPersistenceManager = inject(configAdmin, encryptingPersistenceManager);
        }
    }

    /**
     * Replaces original Felix' PersistenceManager with our version
     * @param configAdmin
     * @param pm
     * @return
     */
    private PersistenceManager inject(ConfigurationAdmin configAdmin, PersistenceManager pm) {
        try {
            Field configurationManager = configAdmin.getClass().getDeclaredField("configurationManager");
            configurationManager.setAccessible(true);
            Object configurationManagerValue = configurationManager.get(configAdmin);
            Field persistenceManagers = configurationManagerValue.getClass().getDeclaredField("persistenceManagers");
            persistenceManagers.setAccessible(true);
            Object[] persistenceManagersValue = (Object[]) persistenceManagers.get(configurationManagerValue);
            if (persistenceManagersValue != null && persistenceManagersValue.length == 1) {
                // replace org.apache.felix.cm.impl.CachingPersistenceManagerProxy.pm
                Field pmField = persistenceManagersValue[0].getClass().getDeclaredField("pm");
                pmField.setAccessible(true);
                PersistenceManager originalPM = (PersistenceManager) pmField.get(persistenceManagersValue[0]);
                pmField.set(persistenceManagersValue[0], pm);
                return originalPM;
            }
        } catch (Exception e) {
            LOG.warn(e.getMessage());
        }
        return null;
    }

    @Override
    public String getScheme() {
        return RESOLVER_SCHEME;
    }

    @Override
    public String resolve(FabricService fabricService, Map<String, Map<String, String>> configs, String pid, String key, String value) {
        if (encryptor == null) {
            encryptor = getEncryptor(fabricService);
        }
        String decrypted = encryptor.decrypt(value.substring(RESOLVER_SCHEME.length() + 1));
        if (configs != null) {
            // ENTESB-5392: let's keep encrypted value too
            Map<String, String> pidConfiguration = configs.get(pid);
            if (!pidConfiguration.containsKey("fabric.zookeeper.encrypted.values")) {
                pidConfiguration.put("fabric.zookeeper.encrypted.values", "");
            }
            String encryptedValues = pidConfiguration.get("fabric.zookeeper.encrypted.values");
            if (!encryptedValues.isEmpty()) {
                encryptedValues += ", ";
            }
            encryptedValues += key;
            pidConfiguration.put("fabric.zookeeper.encrypted.values", encryptedValues);
            pidConfiguration.put(key + ".encrypted", value);
        }

        return decrypted;
    }

    private PBEStringEncryptor getEncryptor(FabricService fabricService) {
        StandardPBEStringEncryptor encryptor = new StandardPBEStringEncryptor();
        encryptor.setAlgorithm(getAlgorithm(fabricService));
        encryptor.setPassword(getPassword(fabricService));
        return encryptor;
    }

    private String getAlgorithm(FabricService fabricService) {
        try {
            return getStringData(fabricService.adapt(CuratorFramework.class), AUTHENTICATION_CRYPT_ALGORITHM.getPath());
        } catch (Exception e) {
            throw FabricException.launderThrowable(e);
        }
    }

    private String getPassword(FabricService fabricService) {
        try {
            String pw = getStringData(fabricService.adapt(CuratorFramework.class), AUTHENTICATION_CRYPT_PASSWORD.getPath());
            // the password may be encoded, so we need to decode if needed
            return PasswordEncoder.decode(pw);
        } catch (Exception e) {
            throw FabricException.launderThrowable(e);
        }
    }
}
