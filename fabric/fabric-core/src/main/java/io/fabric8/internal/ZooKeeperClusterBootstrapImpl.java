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
package io.fabric8.internal;

import io.fabric8.api.BootstrapComplete;
import io.fabric8.api.Constants;
import io.fabric8.api.Container;
import io.fabric8.api.CreateEnsembleOptions;
import io.fabric8.api.CuratorComplete;
import io.fabric8.api.DataStoreTemplate;
import io.fabric8.api.FabricComplete;
import io.fabric8.api.FabricException;
import io.fabric8.api.FabricService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.ZooKeeperClusterBootstrap;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.Configurer;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.utils.BundleUtils;
import io.fabric8.utils.OsgiUtils;
import io.fabric8.zookeeper.bootstrap.BootstrapConfiguration;
import io.fabric8.zookeeper.bootstrap.BootstrapConfiguration.DataStoreOptions;
import io.fabric8.zookeeper.bootstrap.DataStoreBootstrapTemplate;

import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import io.fabric8.api.gravia.ServiceLocator;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationEvent;
import org.osgi.service.cm.ConfigurationListener;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZooKeeperClusterBootstrap
 * |_ ConfigurationAdmin
 * |_ BootstrapConfiguration (@see BootstrapConfiguration)
 */
@ThreadSafe
@Component(name = "io.fabric8.zookeeper.cluster.bootstrap", label = "Fabric8 ZooKeeper Cluster Bootstrap", immediate = true, metatype = false)
@Service(ZooKeeperClusterBootstrap.class)
public final class ZooKeeperClusterBootstrapImpl extends AbstractComponent implements ZooKeeperClusterBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZooKeeperClusterBootstrapImpl.class);

    @Reference
    private Configurer configurer;

    @Reference(referenceInterface = BootstrapConfiguration.class)
    private final ValidatingReference<BootstrapConfiguration> bootstrapConfiguration = new ValidatingReference<BootstrapConfiguration>();
    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configAdmin = new ValidatingReference<ConfigurationAdmin>();
    @Reference(referenceInterface = RuntimeProperties.class, bind = "bindRuntimeProperties", unbind = "unbindRuntimeProperties")
    private final ValidatingReference<RuntimeProperties> runtimeProperties = new ValidatingReference<RuntimeProperties>();

    @Property(name = "name", label = "Container Name", description = "The name of the container", value = "${runtime.id}")
    private String name;
    @Property(name = "homeDir", label = "Container Home", description = "The home directory of the container", value = "${runtime.home}")
    private File homeDir;
    @Property(name = "data", label = "Container Data", description = "The data directory of the container", value = "${runtime.data}")
    private String data;

    private BundleContext bundleContext;

    @Activate
    void activate(BundleContext bundleContext, Map<String, ?> configuration) throws Exception {
        this.bundleContext = bundleContext;
        this.configurer.configure(configuration, this);
        BootstrapConfiguration bootConfig = bootstrapConfiguration.get();
        CreateEnsembleOptions options = bootConfig.getBootstrapOptions();
        if (options.isEnsembleStart()) {
            startBundles(options);
        }
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }

    @Override
    public void create(CreateEnsembleOptions options) {
        assertValid();
        try {
            // Wait for bootstrap to be complete
            ServiceLocator.awaitService(BootstrapComplete.class);

            LOGGER.info("Create fabric with: {}", options);

            stopBundles();

            BundleContext syscontext = bundleContext.getBundle(0).getBundleContext();
            long bootstrapTimeout = options.getBootstrapTimeout();

            RuntimeProperties runtimeProps = runtimeProperties.get();
            BootstrapConfiguration bootConfig = bootstrapConfiguration.get();
            if (options.isClean()) {
                bootConfig = cleanInternal(syscontext, bootConfig, runtimeProps);
            }

            // before we start fabric, register CM listener that'll mark end of work of FabricConfigAdminBridge
            final CountDownLatch fcabLatch = new CountDownLatch(1);
            final ServiceRegistration<ConfigurationListener> registration = bundleContext.registerService(ConfigurationListener.class, new ConfigurationListener() {
                @Override
                public void configurationEvent(ConfigurationEvent event) {
                    if (event.getType() == ConfigurationEvent.CM_UPDATED && event.getPid() != null
                            && event.getPid().equals(Constants.CONFIGADMIN_BRIDGE_PID)) {
                        fcabLatch.countDown();
                    }
                }
            }, null);

            BootstrapCreateHandler createHandler = new BootstrapCreateHandler(syscontext, bootConfig, runtimeProps);
            createHandler.bootstrapFabric(name, homeDir, options);

            startBundles(options);

            long startTime = System.currentTimeMillis();

            ServiceLocator.awaitService(FabricComplete.class, bootstrapTimeout, TimeUnit.MILLISECONDS);

            // FabricComplete is registered somewhere in the middle of registering CuratorFramework (SCR activates
            // it when CuratorFramework is registered), but CuratorFramework leads to activation of >100 SCR
            // components, so let's wait for new CuratorComplete service - it is registered after registration
            // of CuratorFramework finishes
            ServiceLocator.awaitService(CuratorComplete.class, bootstrapTimeout, TimeUnit.MILLISECONDS);
            CuratorFramework curatorFramework = ServiceLocator.getService(CuratorFramework.class);
            Map<String, String> dataStoreProperties = options.getDataStoreProperties();
            if(ZooKeeperUtils.exists(curatorFramework, ZkPath.CONFIG_GIT_EXTERNAL.getPath()) == null)
                ZooKeeperUtils.create(curatorFramework, ZkPath.CONFIG_GIT_EXTERNAL.getPath());
            if(dataStoreProperties != null){
                String remoteUrl = dataStoreProperties.get(Constants.GIT_REMOTE_URL);
                if(remoteUrl != null) {
                    ZooKeeperUtils.create(curatorFramework, ZkPath.CONFIG_GIT_EXTERNAL_URL.getPath());
                    ZooKeeperUtils.add(curatorFramework, ZkPath.CONFIG_GIT_EXTERNAL_URL.getPath(), remoteUrl);
                }
                String remoteUser = dataStoreProperties.get("gitRemoteUser");
                if(remoteUser != null) {
                    ZooKeeperUtils.create(curatorFramework, ZkPath.CONFIG_GIT_EXTERNAL_USER.getPath());
                    ZooKeeperUtils.add(curatorFramework, ZkPath.CONFIG_GIT_EXTERNAL_USER.getPath(), remoteUser);
                }
                String remotePassword = dataStoreProperties.get("gitRemotePassword");
                if(remotePassword != null) {
                    ZooKeeperUtils.create(curatorFramework, ZkPath.CONFIG_GIT_EXTERNAL_PASSWORD.getPath());
                    ZooKeeperUtils.add(curatorFramework, ZkPath.CONFIG_GIT_EXTERNAL_PASSWORD.getPath(), remotePassword);
                }
            }

            // HttpService is registered differently. not as SCR component activation, but after
            // FabricConfigAdminBridge updates (or doesn't touch) org.ops4j.pax.web CM configuration
            // however in fabric, this PID contains URL to jetty configuration in the form "profile:jetty.xml"
            // so we have to have FabricService and ProfileUrlHandler active
            // some ARQ (single container instance) tests failed because tests ended without http service running
            // of course we have to think if all fabric instances need http service
            ServiceLocator.awaitService("org.osgi.service.http.HttpService", bootstrapTimeout, TimeUnit.MILLISECONDS);

            // and last wait - too much synchronization never hurts
            fcabLatch.await(bootstrapTimeout, TimeUnit.MILLISECONDS);
            registration.unregister();

            long timeDiff = System.currentTimeMillis() - startTime;
            createHandler.waitForContainerAlive(name, syscontext, bootstrapTimeout);

            if (options.isWaitForProvision() && options.isAgentEnabled()) {
                long currentTime = System.currentTimeMillis();
                createHandler.waitForSuccessfulDeploymentOf(name, syscontext, bootstrapTimeout - (currentTime - startTime));
            }
        } catch (RuntimeException rte) {
            throw rte;
        } catch (Exception ex) {
            throw new FabricException("Unable to create zookeeper server configuration", ex);
        }
    }

    private BootstrapConfiguration cleanInternal(final BundleContext syscontext, final BootstrapConfiguration bootConfig, RuntimeProperties runtimeProps) throws TimeoutException {
        LOGGER.debug("Begin clean fabric");
        try {
            Configuration zkClientCfg = null;
            Configuration zkServerCfg = null;
            Configuration[] configsSet = configAdmin.get().listConfigurations("(|(service.factoryPid=io.fabric8.zookeeper.server)(service.pid=io.fabric8.zookeeper))");
            if (configsSet != null) {
                for (Configuration cfg : configsSet) {
                    // let's explicitly delete client config first
                    if ("io.fabric8.zookeeper".equals(cfg.getPid())) {
                        zkClientCfg = cfg;
                    }
                    if ("io.fabric8.zookeeper.server".equals(cfg.getFactoryPid())) {
                        zkServerCfg = cfg;
                    }
                }
            }
            File karafData = new File(data);

            // Setup the listener for unregistration of {@link BootstrapConfiguration}
            final CountDownLatch unregisterLatch = new CountDownLatch(1);
            ServiceListener listener = new ServiceListener() {
                @Override
                public void serviceChanged(ServiceEvent event) {
                    if (event.getType() == ServiceEvent.UNREGISTERING) {
                        LOGGER.debug("Unregistering BootstrapConfiguration");
                        bootConfig.getComponentContext().getBundleContext().removeServiceListener(this);
                        unregisterLatch.countDown();
                    }
                }
            };
            String filter = "(objectClass=" + BootstrapConfiguration.class.getName() + ")";
            // FABRIC-1052: register listener using the same bundle context that is used for listeners related to SCR
            bootConfig.getComponentContext().getBundleContext().addServiceListener(listener, filter);

            CountDownLatch unregisterLatch2 = null;
            if (syscontext.getServiceReference(CuratorComplete.class) != null) {
                unregisterLatch2 = new CountDownLatch(1);
                final CountDownLatch finalUnregisterLatch = unregisterLatch2;
                listener = new ServiceListener() {
                    @Override
                    public void serviceChanged(ServiceEvent event) {
                        if (event.getType() == ServiceEvent.UNREGISTERING) {
                            LOGGER.debug("Unregistering CuratorComplete");
                            bootConfig.getComponentContext().getBundleContext().removeServiceListener(this);
                            finalUnregisterLatch.countDown();
                        }
                    }
                };
                bootConfig.getComponentContext().getBundleContext().addServiceListener(listener, "(objectClass=" + CuratorComplete.class.getName() + ")");
            }

            // Disable the BootstrapConfiguration component
            // ENTESB-4827: disabling BootstrapConfiguration leads to deactivation of FabricService and ProfileUrlHandler
            // and we have race condition if we're --cleaning after recently created fabric. previous fabric
            // started FabricConfigAdminBridge which scheduled CM updates for tens of PIDs - among others,
            // org.ops4j.pax.web, which leads to an attempt to reconfigure Jetty with "profile:jetty.xml"
            // and if we disable ProfileUrlHandler we may loose Jetty instance
            LOGGER.debug("Disable BootstrapConfiguration");
            ComponentContext componentContext = bootConfig.getComponentContext();
            componentContext.disableComponent(BootstrapConfiguration.COMPONENT_NAME);

            if (!unregisterLatch.await(30, TimeUnit.SECONDS))
                throw new TimeoutException("Timeout for unregistering BootstrapConfiguration service");
            if (unregisterLatch2 != null && !unregisterLatch2.await(30, TimeUnit.SECONDS))
                throw new TimeoutException("Timeout for unregistering CuratorComplete service");

            // Do the cleanup
            runtimeProps.clearRuntimeAttributes();
            cleanConfigurations(syscontext, zkClientCfg, zkServerCfg);
            cleanZookeeperDirectory(karafData);
            cleanGitDirectory(karafData);

            // Setup the registration listener for the new {@link BootstrapConfiguration}
            final CountDownLatch registerLatch = new CountDownLatch(1);
            final AtomicReference<ServiceReference<?>> sref = new AtomicReference<ServiceReference<?>>();
            listener = new ServiceListener() {
                @Override
                public void serviceChanged(ServiceEvent event) {
                    if (event.getType() == ServiceEvent.REGISTERED) {
                        LOGGER.debug("Registered BootstrapConfiguration");
                        syscontext.removeServiceListener(this);
                        sref.set(event.getServiceReference());
                        registerLatch.countDown();
                    }
                }
            };
            syscontext.addServiceListener(listener, "(objectClass=" + BootstrapConfiguration.class.getName() + ")");

            // Enable the {@link BootstrapConfiguration} component and await the registration of the respective service
            LOGGER.debug("Enable BootstrapConfiguration");
            componentContext.enableComponent(BootstrapConfiguration.COMPONENT_NAME);
            if (!registerLatch.await(30, TimeUnit.SECONDS))
                throw new TimeoutException("Timeout for registering BootstrapConfiguration service");

            return (BootstrapConfiguration) syscontext.getService(sref.get());

        } catch (RuntimeException rte) {
            throw rte;
        } catch (TimeoutException toe) {
            throw toe;
        } catch (Exception ex) {
            throw new FabricException("Unable to delete zookeeper configuration", ex);
        } finally {
            LOGGER.debug("End clean fabric");
        }
    }

    private void cleanConfigurations(BundleContext sysContext, Configuration zkClientCfg, Configuration zkServerCfg) throws IOException, InvalidSyntaxException, InterruptedException {
        if (zkClientCfg != null) {
            LOGGER.debug("cleanConfiguration: {}", zkClientCfg);
            OsgiUtils.deleteCmConfigurationAndWait(sysContext, zkClientCfg, Constants.ZOOKEEPER_CLIENT_PID, 20, TimeUnit.SECONDS);
        }
        if (zkServerCfg != null) {
            LOGGER.debug("cleanConfiguration: {}", zkServerCfg);
            OsgiUtils.deleteCmFactoryConfigurationAndWait(sysContext, zkServerCfg, Constants.ZOOKEEPER_SERVER_PID, 20, TimeUnit.SECONDS);
        }
    }

    private void cleanZookeeperDirectory(File karafData) throws IOException {
        File zkdir = new File(karafData, "zookeeper");
        if (zkdir.isDirectory()) {
            LOGGER.debug("cleanZookeeperDirectory: {}", zkdir);
            File renamed = new File(karafData, "zookeeper." + System.currentTimeMillis());
            if (!zkdir.renameTo(renamed)) {
                throw new IOException("Cannot rename zookeeper data dir for removal: " + zkdir);
            }
            delete(renamed);
        }
    }

    private void cleanGitDirectory(File karafData) throws IOException {
        File gitdir = new File(karafData, "git");
        if (gitdir.isDirectory()) {
            LOGGER.debug("cleanGitDirectory: {}", gitdir);
            File renamed = new File(karafData, "git." + System.currentTimeMillis());
            if (!gitdir.renameTo(renamed)) {
                throw new IOException("Cannot rename git data dir for removal: " + gitdir);
            }
            delete(renamed);
        }
    }

    private void stopBundles() throws BundleException {
        BundleUtils bundleUtils = new BundleUtils(bundleContext);
        bundleUtils.findAndStopBundle("io.fabric8.fabric-agent");
//        bundleUtils.findAndStopBundle("org.ops4j.pax.web.pax-web-jetty");
    }

    private void startBundles(CreateEnsembleOptions options) throws BundleException {
        BundleUtils bundleUtils = new BundleUtils(bundleContext);
        Bundle agentBundle = bundleUtils.findBundle("io.fabric8.fabric-agent");
        if (agentBundle != null && options.isAgentEnabled()) {
            agentBundle.start();
        }
//        Bundle webBundle = bundleUtils.findBundle("org.ops4j.pax.web.pax-web-jetty");
//        if (webBundle != null) {
//            webBundle.start();
//        }
    }

    private static void delete(File dir) {
        if (dir.isDirectory()) {
            for (File child : dir.listFiles()) {
                delete(child);
            }
        }
        if (dir.exists()) {
            try {
                boolean deleted = dir.delete();
                if(!deleted) {
                    LOGGER.warn("Failed to delete dir {}", dir);
                }
            } catch(SecurityException e) {
                LOGGER.warn("Failed to delete dir {} due to {}", dir, e);
            }
        }
    }

    void bindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.bind(service);
    }

    void unbindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.unbind(service);
    }

    void bindBootstrapConfiguration(BootstrapConfiguration service) {
        this.bootstrapConfiguration.bind(service);
    }

    void unbindBootstrapConfiguration(BootstrapConfiguration service) {
        this.bootstrapConfiguration.unbind(service);
    }

    void bindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.bind(service);
    }
    void unbindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.unbind(service);
    }
    
    /**
     * This static bootstrap create handler does not have access to the {@link ZooKeeperClusterBootstrap} state.
     * It operates on the state that it is given, which is unrelated to this component.
     */
    static class BootstrapCreateHandler {
        private final BootstrapConfiguration bootConfig;
        private final RuntimeProperties runtimeProperties;
        private final BundleContext sysContext;

        BootstrapCreateHandler(BundleContext sysContext, BootstrapConfiguration bootConfig, RuntimeProperties runtimeProperties) {
            this.sysContext = sysContext;
            this.bootConfig = bootConfig;
            this.runtimeProperties = runtimeProperties;
        }

        void bootstrapFabric(String containerId, File homeDir, CreateEnsembleOptions options) throws Exception {
            String connectionUrl = bootConfig.getConnectionUrl(options);
            DataStoreOptions bootOptions = new DataStoreOptions(containerId, homeDir, connectionUrl, options);
            runtimeProperties.putRuntimeAttribute(DataStoreTemplate.class, new DataStoreBootstrapTemplate(bootOptions));

            bootConfig.createOrUpdateDataStoreConfig(options);

            boolean success = bootConfig.createZooKeeeperServerConfig(sysContext, options);
            if (!success) {
                throw new TimeoutException("Timeout waiting for " + Constants.ZOOKEEPER_SERVER_PID + " configuration update");
            }

            success = bootConfig.createZooKeeeperClientConfig(sysContext, connectionUrl, options);
            if (!success) {
                throw new TimeoutException("Timeout waiting for " + Constants.ZOOKEEPER_CLIENT_PID + " configuration update");
            }
        }

        private void waitForContainerAlive(String containerName, BundleContext syscontext, long timeout) throws TimeoutException {
            System.out.println(String.format("Waiting for container: %s", containerName));

            Exception lastException = null;
            long now = System.currentTimeMillis();
            long end = now + timeout;
            while (!Thread.interrupted() && (now = System.currentTimeMillis()) < end) {
                FabricService fabricService = ServiceLocator.getRequiredService(FabricService.class);
                try {
                    Container container = fabricService.getContainer(containerName);
                    if (container != null && container.isAlive()) {
                        return;
                    } 
                } catch (Exception ex) {
                    lastException = ex;
                }
                if (lastException != null) {
                    LOGGER.debug("lastException = " + lastException);
                }

                try { 
                    Thread.sleep(500);      
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    LOGGER.debug("Sleep was interrupted: " + ex);
                }
            }
            TimeoutException toex = new TimeoutException("Cannot create container in time");
            if (lastException != null) {
                toex.initCause(lastException);
            }
            throw toex;
        }

        private void waitForSuccessfulDeploymentOf(String containerName, BundleContext syscontext, long timeout) throws TimeoutException {
            System.out.println(String.format("Waiting for container %s to provision.", containerName));

            Exception lastException = null;
            long startedAt = System.currentTimeMillis();
            while (!Thread.interrupted() && System.currentTimeMillis() < startedAt + timeout) {
                ServiceReference<FabricService> sref = syscontext.getServiceReference(FabricService.class);
                FabricService fabricService = sref != null ? syscontext.getService(sref) : null;
                try {
                    Container container = fabricService != null ? fabricService.getContainer(containerName) : null;
                    if (container != null && container.isAlive() && "success".equals(container.getProvisionStatus())) {
                        return;
                    } else {
                        Thread.sleep(500);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    lastException = ex;
                } catch (Exception ex) {
                    lastException = ex;
                }
            }
            TimeoutException toex = new TimeoutException("Cannot provision container in time");
            if (lastException != null) {
                toex.initCause(lastException);
            }
            throw toex;
        }
    }
}
