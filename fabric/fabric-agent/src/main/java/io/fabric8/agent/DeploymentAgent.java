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
package io.fabric8.agent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.fabric8.agent.download.DownloadCallback;
import io.fabric8.agent.download.DownloadManager;
import io.fabric8.agent.download.DownloadManagers;
import io.fabric8.agent.download.Downloader;
import io.fabric8.agent.download.StreamProvider;
import io.fabric8.agent.internal.Macro;
import io.fabric8.agent.service.Agent;
import io.fabric8.agent.service.Constants;
import io.fabric8.agent.service.FeatureConfigInstaller;
import io.fabric8.agent.service.State;
import io.fabric8.api.Container;
import io.fabric8.api.CuratorComplete;
import io.fabric8.api.FabricService;
import io.fabric8.api.Profile;
import io.fabric8.common.util.ChecksumUtils;
import io.fabric8.common.util.Files;
import io.fabric8.maven.MavenResolver;
import io.fabric8.maven.MavenResolvers;
import io.fabric8.patch.FabricPatchService;
import io.fabric8.patch.management.PatchManagement;
import io.fabric8.utils.NamedThreadFactory;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.utils.properties.Properties;
import org.apache.felix.utils.version.VersionRange;
import org.apache.zookeeper.data.Stat;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.resource.Resource;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.fabric8.agent.resolver.ResourceUtils.getUri;
import static io.fabric8.agent.service.Constants.DEFAULT_BUNDLE_UPDATE_RANGE;
import static io.fabric8.agent.service.Constants.DEFAULT_FEATURE_RESOLUTION_RANGE;
import static io.fabric8.agent.service.Constants.DEFAULT_UPDATE_SNAPSHOTS;
import static io.fabric8.agent.utils.AgentUtils.addMavenProxies;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.exists;

public class DeploymentAgent implements ManagedService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeploymentAgent.class);

    private static final String DEFAULT_DOWNLOAD_THREADS = "4";
    private static final String DOWNLOAD_THREADS = "io.fabric8.agent.download.threads";

    private static long agentCounter = 1;

    private static final String KARAF_HOME = System.getProperty("karaf.home");
    private static final String KARAF_BASE = System.getProperty("karaf.base");
    private static final String KARAF_DATA = System.getProperty("karaf.data");
    private static final String KARAF_ETC = System.getProperty("karaf.etc");
    private static final String SYSTEM_PATH = KARAF_HOME + File.separator + "system";
    private static final String LIB_PATH = KARAF_BASE + File.separator + "lib";
    private static final String LIB_EXT_PATH = LIB_PATH + File.separator + "ext";
    private static final String LIB_ENDORSED_PATH = LIB_PATH + File.separator + "endorsed";

    private static final String STATE_FILE = "state.json";

    private ServiceTracker<FabricService, FabricService> fabricService;
    private ServiceTracker<CuratorComplete, CuratorComplete> curatorCompleteService;

    private final ExecutorService executor;
    private final ScheduledExecutorService downloadExecutor;

    private final BundleContext bundleContext;
    private final BundleContext systemBundleContext;
    private final Properties libChecksums;
    private final Properties endorsedChecksums;
    private final Properties extensionChecksums;
    private final Properties etcChecksums;

    private final Properties managedLibs;
    private final Properties managedEndorsedLibs;
    private final Properties managedExtensionLibs;
    private final Properties managedSysProps;
    private final Properties managedConfigProps;
    private final Properties managedEtcs;
    private volatile String provisioningStatus;
    private volatile Throwable provisioningError;
    private volatile Collection<Resource> provisionList;
    private volatile boolean requiresRestart = false;
    private volatile boolean fabricNotAvailableLogged;

    private volatile String httpUrl;
    private volatile List<URI> mavenRepoURIs = new ArrayList<URI>();

    // lock to operate on fabricService.getCurrentContainer().getHttpUrl()
    // and service.getMavenRepoURIs()
    // see ENTESB-2370: OSE Maven artifacts uploaded to fabric proxy cannot be resolved by containers
    private Lock fabricServiceOperations = new ReentrantLock();

    private final State state = new State();

    private final String deploymentAgentId;

    public DeploymentAgent(BundleContext bundleContext) throws IOException {
        this.bundleContext = bundleContext;
        this.systemBundleContext = bundleContext.getBundle(0).getBundleContext();
        this.libChecksums = new Properties(bundleContext.getDataFile("lib-checksums.properties"));
        this.endorsedChecksums = new Properties(bundleContext.getDataFile("endorsed-checksums.properties"));
        this.extensionChecksums = new Properties(bundleContext.getDataFile("extension-checksums.properties"));
        this.etcChecksums = new Properties(bundleContext.getDataFile("etc-checksums.properties"));
        this.managedSysProps = new Properties(bundleContext.getDataFile("system.properties"));
        this.managedConfigProps = new Properties(bundleContext.getDataFile("config.properties"));
        this.managedLibs  = new Properties(bundleContext.getDataFile("libs.properties"));
        this.managedEndorsedLibs  = new Properties(bundleContext.getDataFile("endorsed.properties"));
        this.managedExtensionLibs  = new Properties(bundleContext.getDataFile("extension.properties"));
        this.managedEtcs = new Properties(bundleContext.getDataFile("etc.properties"));
        String revision = bundleContext.getBundle().adapt(BundleRevision.class).toString();
        deploymentAgentId = String.format("fabric-agent-%s.%s", revision, agentCounter++);
        this.executor = Executors.newSingleThreadExecutor(new NamedThreadFactory(deploymentAgentId));
        this.downloadExecutor = createDownloadExecutor();

        fabricService = new ServiceTracker<>(systemBundleContext, FabricService.class, new ServiceTrackerCustomizer<FabricService, FabricService>() {
            @Override
            public FabricService addingService(ServiceReference<FabricService> reference) {
                FabricService service = systemBundleContext.getService(reference);
                if (provisioningStatus != null) {
                    updateStatus(service, provisioningStatus, provisioningError, true);
                }
                return service;
            }

            @Override
            public void modifiedService(ServiceReference<FabricService> reference, FabricService service) {
                if (provisioningStatus != null) {
                    updateStatus(service, provisioningStatus, provisioningError, true);
                }
            }

            @Override
            public void removedService(ServiceReference<FabricService> reference, FabricService service) {
                // TODO: what if Config Admin causes invocation of doUpdate()? should we keep old httpUrl and mavenRepoURIs?
            }
        });
        fabricService.open();
        curatorCompleteService = new ServiceTracker<CuratorComplete, CuratorComplete>(systemBundleContext, CuratorComplete.class, null);
        curatorCompleteService.open();
    }

    private void updateMavenRepositoryConfiguration(FabricService service) {
        LOGGER.info("Updating Maven Repository Configuration");
        try {
            fabricServiceOperations.lock();
            httpUrl = service.getCurrentContainer().getHttpUrl();
            mavenRepoURIs = service.getMavenRepoURIs();
            LOGGER.info("Maven repository configuration correctly updated: httpUrl=[{}], mavenRepoURIs=[{}]", httpUrl, mavenRepoURIs);
        } catch (RuntimeException e){
            LOGGER.info("It's been impossible to correctly update maven repositories configuration");
            if(LOGGER.isTraceEnabled()){
                LOGGER.trace("Detailed Exception", e);
            }
        }
        finally {
            fabricServiceOperations.unlock();
        }
    }

    protected ScheduledExecutorService createDownloadExecutor() {
        // TODO: this should not be loaded from a static file
        // TODO: or at least from the bundle context, but preferably from the config
        String size = DEFAULT_DOWNLOAD_THREADS;
        try {
            Properties customProps = new Properties(new File(KARAF_BASE + File.separator + "etc" + File.separator + "custom.properties"));
            size = customProps.getProperty(DOWNLOAD_THREADS, size);
        } catch (Exception e) {
            // ignore
        }
        int num = Integer.parseInt(size);
        LOGGER.info("Creating fabric-agent-download thread pool with size: {}", num);
        return Executors.newScheduledThreadPool(num, new NamedThreadFactory("fabric-agent-download"));
    }

    public void start() throws IOException {
        LOGGER.info("Starting DeploymentAgent " + deploymentAgentId);
        loadLibChecksums(LIB_PATH, libChecksums);
        loadLibChecksums(LIB_ENDORSED_PATH, endorsedChecksums);
        loadLibChecksums(LIB_EXT_PATH, extensionChecksums);
        loadLibChecksums(KARAF_ETC, etcChecksums);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                LOGGER.info("DeploymentAgent ready to accept configadmin tasks");
            }
        });
    }

    public void stop() throws InterruptedException {
        LOGGER.info("Stopping DeploymentAgent " + deploymentAgentId);
        // We can't wait for the threads to finish because the agent needs to be able to
        // update itself and this would cause a deadlock
        synchronized (executor) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    LOGGER.info("DeploymentAgent won't accept new configadmin tasks");
                }
            });
            executor.shutdown();
        }
        downloadExecutor.shutdown();
        fabricService.close();
        curatorCompleteService.close();
    }

    private void loadLibChecksums(String path, Properties props) throws IOException {
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create fabric lib directory at:" + dir.getAbsolutePath());
        }

        for (String lib : dir.list()) {
            File f = new File(path, lib);
            if (f.exists() && f.isFile()) {
                props.put(lib, Long.toString(ChecksumUtils.checksum(new FileInputStream(f))));
            }
        }
        props.save();
    }

    public void updated(final Dictionary<String, ?> props) throws ConfigurationException {
        LOGGER.info("DeploymentAgent {} updated with {}", deploymentAgentId, props);
        synchronized (executor) {
            if (executor.isShutdown() || props == null) {
                return;
            }
            executor.submit(new Runnable() {
                public void run() {
                    Throwable result = null;
                    boolean success = false;
                    try {
                        success = doUpdate(props);
                    } catch (Throwable e) {
                        result = e;
                        LOGGER.error("Unable to update agent", e);
                    }
                    // This update is critical, so
                    if (success || result != null) {
                        updateStatus(success ? Container.PROVISION_SUCCESS : Container.PROVISION_ERROR, result, true);
                    }
                }
            });
        }
    }

    private void updateStatus(String status, Throwable result) {
        updateStatus(status, result, false);
    }

    private void updateStatus(String status, Throwable result, boolean force) {
        try {
            FabricService fs;
            if (force) {
                fs = fabricService.waitForService(0);
            } else {
                fs = fabricService.getService();
            }
            updateStatus(fs, status, result, force);
        } catch (Throwable e) {
            LOGGER.warn("Unable to set provisioning result");
        }
    }

    // last time the status was updated
    // synchronization is not that important here
    private long lastStatusUpdate = 0L;
    // ENTESB-3361: we'll be updating status (in ZK) not faster than every UPDATE_INTERVAL ms
    private static final long UPDATE_INTERVAL = 2000L;

    private void updateStatus(FabricService fs, String status, Throwable result, boolean force/*=false*/) {
        if (!force && System.currentTimeMillis() < lastStatusUpdate + UPDATE_INTERVAL) {
            return;
        }
        lastStatusUpdate = System.currentTimeMillis();
        try {
            provisioningStatus = status;
            provisioningError = result;

            if (fs != null) {
                fabricNotAvailableLogged = false;
                Container container = fs.getCurrentContainer();
                String e;
                if (result == null) {
                    e = null;
                } else {
                    StringWriter sw = new StringWriter();
                    result.printStackTrace(new PrintWriter(sw));
                    e = sw.toString();
                }
                if (provisionList != null) {
                    Set<String> uris = new TreeSet<>();
                    for (Resource res : provisionList) {
                        uris.add(getUri(res));
                    }
                    container.setProvisionList(new ArrayList<>(uris));
                }
                container.setProvisionResult(status);
                container.setProvisionException(e);

                java.util.Properties provisionChecksums = new java.util.Properties();

                for (Map.Entry<Long, Long> entry : state.bundleChecksums.entrySet()) {
                    Bundle bundle = systemBundleContext.getBundle(entry.getKey());
                    String location = bundle.getLocation();
                    provisionChecksums.put(location, entry.getValue().toString());
                }
/*
                putAllProperties(provisionChecksums, libChecksums);
                putAllProperties(provisionChecksums, endorsedChecksums);
                putAllProperties(provisionChecksums, extensionChecksums);
*/
                container.setProvisionChecksums(provisionChecksums);
            } else {
                if (!fabricNotAvailableLogged) {
                    fabricNotAvailableLogged = true;
                    LOGGER.info("Unable to set provisioning status as FabricService is not available");
                }
            }
        } catch (Throwable e) {
            LOGGER.warn("Unable to set provisioning result");
        }
    }

    protected static void putAllProperties(java.util.Properties answer, Properties properties) {
        Set<Map.Entry<String, String>> entries = properties.entrySet();
        for (Map.Entry<String, String> entry : entries) {
            answer.put(entry.getKey(), entry.getValue());
        }
    }

    public boolean doUpdate(Dictionary<String, ?> props) throws Exception {
        if (props == null || Boolean.parseBoolean((String) props.get("disabled"))) {
            return false;
        }

        final Hashtable<String, String> properties = new Hashtable<>();
        for (Enumeration e = props.keys(); e.hasMoreElements();) {
            Object key = e.nextElement();
            Object val = props.get(key);
            if (!"service.pid".equals(key) && !FeatureConfigInstaller.FABRIC_ZOOKEEPER_PID.equals(key)) {
                properties.put(key.toString(), val.toString());
            }
        }

        updateStatus("analyzing", null);

        // Building configuration
        curatorCompleteService.waitForService(TimeUnit.SECONDS.toMillis(30));
        String httpUrl;
        List<URI> mavenRepoURIs;

        //force reading of updated informations from ZK
        if (!fabricService.isEmpty()) {
            updateMavenRepositoryConfiguration(fabricService.getService());
        }

        try {
            fabricServiceOperations.lock();
            // no one will change the members now
            httpUrl = this.httpUrl;
            mavenRepoURIs = this.mavenRepoURIs;
        } finally {
            fabricServiceOperations.unlock();
        }
        addMavenProxies(properties, httpUrl, mavenRepoURIs);
        final MavenResolver resolver = MavenResolvers.createMavenResolver(properties, "org.ops4j.pax.url.mvn");
        final DownloadManager manager = DownloadManagers.createDownloadManager(resolver, getDownloadExecutor());
        manager.addListener(new DownloadCallback() {
            @Override
            public void downloaded(StreamProvider provider) throws Exception {
                int pending = manager.pending();
                updateStatus(pending > 0 ? "downloading (" + pending + " pending)" : "downloading", null);
            }
        });


        // Update framework, libs, system and config props
        final Object lock = new Object();
        final AtomicBoolean restart = new AtomicBoolean();
        final Set<String> libsToRemove = new HashSet<>(managedLibs.keySet());
        final Set<String> endorsedLibsToRemove = new HashSet<>(managedEndorsedLibs.keySet());
        final Set<String> extensionLibsToRemove = new HashSet<>(managedExtensionLibs.keySet());
        final Set<String> sysPropsToRemove = new HashSet<>(managedSysProps.keySet());
        final Set<String> configPropsToRemove = new HashSet<>(managedConfigProps.keySet());
        final Set<String> etcsToRemove = new HashSet<>(managedEtcs.keySet());
        final Properties configProps = new Properties(new File(KARAF_BASE + File.separator + "etc" + File.separator + "config.properties"));
        final Properties systemProps = new Properties(new File(KARAF_BASE + File.separator + "etc" + File.separator + "system.properties"));

        Downloader downloader = manager.createDownloader();
        for (String key : properties.keySet()) {
            if (key.equals("framework")) {
                String url = properties.get(key);
                if (!url.startsWith("mvn:")) {
                    throw new IllegalArgumentException("Framework url must use the mvn: protocol");
                }
                downloader.download(url, new DownloadCallback() {
                    @Override
                    public void downloaded(StreamProvider provider) throws Exception {
                        File file = provider.getFile();
                        String path = file.getPath();
                        if (path.startsWith(KARAF_HOME)) {
                            path = path.substring(KARAF_HOME.length() + 1);
                        }
                        synchronized (lock) {
                            if (!path.equals(configProps.get("karaf.framework.felix"))) {
                                configProps.put("karaf.framework", "felix");
                                configProps.put("karaf.framework.felix", path);
                                restart.set(true);
                            }
                        }
                    }
                });
            } else if (key.startsWith("config.")) {
                String k = key.substring("config.".length());
                String v = properties.get(key);
                synchronized (lock) {
                    managedConfigProps.put(k, v);
                    configPropsToRemove.remove(k);
                    if (!v.equals(configProps.get(k))) {
                        configProps.put(k, v);
                        restart.set(true);
                    }
                }
            } else if (key.startsWith("system.")) {
                String k = key.substring("system.".length());
                synchronized (lock) {
                    String v = properties.get(key);
                    managedSysProps.put(k, v);
                    sysPropsToRemove.remove(k);
                    if (!v.equals(systemProps.get(k))) {
                        systemProps.put(k, v);
                        restart.set(true);
                    }
                }
            } else if (key.startsWith("lib.")) {
                String value = properties.get(key);
                downloader.download(value, new DownloadCallback() {
                    @Override
                    public void downloaded(StreamProvider provider) throws Exception {
                        File libFile = provider.getFile();
                        String libName = libFile.getName();
                        Long checksum = ChecksumUtils.checksum(libFile);
                        boolean update;
                        synchronized (lock) {
                            managedLibs.put(libName, "true");
                            libsToRemove.remove(libName);
                            update = !Long.toString(checksum).equals(libChecksums.getProperty(libName));
                        }
                        if (update) {
                            Files.copy(libFile, new File(LIB_PATH, libName));
                            restart.set(true);
                        }
                    }
                });
            } else if (key.startsWith("endorsed.")) {
                String value = properties.get(key);
                downloader.download(value, new DownloadCallback() {
                    @Override
                    public void downloaded(StreamProvider provider) throws Exception {
                        File libFile = provider.getFile();
                        String libName = libFile.getName();
                        Long checksum = ChecksumUtils.checksum(new FileInputStream(libFile));
                        boolean update;
                        synchronized (lock) {
                            managedEndorsedLibs.put(libName, "true");
                            endorsedLibsToRemove.remove(libName);
                            update = !Long.toString(checksum).equals(endorsedChecksums.getProperty(libName));
                        }
                        if (update) {
                            Files.copy(libFile, new File(LIB_ENDORSED_PATH, libName));
                            restart.set(true);
                        }
                    }
                });
            } else if (key.startsWith("extension.")) {
                String value = properties.get(key);
                downloader.download(value, new DownloadCallback() {
                    @Override
                    public void downloaded(StreamProvider provider) throws Exception {
                        File libFile = provider.getFile();
                        String libName = libFile.getName();
                        Long checksum = ChecksumUtils.checksum(libFile);
                        boolean update;
                        synchronized (lock) {
                            managedExtensionLibs.put(libName, "true");
                            extensionLibsToRemove.remove(libName);
                            update = !Long.toString(checksum).equals(extensionChecksums.getProperty(libName));
                        }
                        if (update) {
                            Files.copy(libFile, new File(LIB_EXT_PATH, libName));
                            restart.set(true);
                        }
                    }
                });
            } else if (key.startsWith("etc.")) {
                String value = properties.get(key);
                downloader.download(value, new DownloadCallback() {
                    @Override
                    public void downloaded(StreamProvider provider) throws Exception {
                        File etcFile = provider.getFile();
                        String etcName = etcFile.getName();
                        Long checksum = ChecksumUtils.checksum(new FileInputStream(etcFile));
                        boolean update;
                        synchronized (lock) {
                            managedEtcs.put(etcName, "true");
                            etcsToRemove.remove(etcName);
                            update = !Long.toString(checksum).equals(etcChecksums.getProperty(etcName));
                        }
                        if (update) {
                            Files.copy(etcFile, new File(KARAF_ETC, etcName));
                        }
                    }
                });
            }
        }
        downloader.await();
        //Remove unused libs, system & config properties
        for (String sysProp : sysPropsToRemove) {
            systemProps.remove(sysProp);
            managedSysProps.remove(sysProp);
            System.clearProperty(sysProp);
            restart.set(true);
        }

        for (String configProp : configPropsToRemove) {
            configProps.remove(configProp);
            managedConfigProps.remove(configProp);
            restart.set(true);
        }

        for (String lib : libsToRemove) {
            File libFile = new File(LIB_PATH, lib);
            libFile.delete();
            libChecksums.remove(lib);
            managedLibs.remove(lib);
            restart.set(true);
        }

        for (String lib : endorsedLibsToRemove) {
            File libFile = new File(LIB_ENDORSED_PATH, lib);
            libFile.delete();
            endorsedChecksums.remove(lib);
            managedEndorsedLibs.remove(lib);
            restart.set(true);
        }

        for (String lib : extensionLibsToRemove) {
            File libFile = new File(LIB_EXT_PATH, lib);
            libFile.delete();
            extensionChecksums.remove(lib);
            managedExtensionLibs.remove(lib);
            restart.set(true);
        }

        for (String etc : etcsToRemove) {
            File etcFile = new File(KARAF_ETC, etc);
            etcFile.delete();
            etcChecksums.remove(etc);
            managedEtcs.remove(etc);
        }

        libChecksums.save();
        endorsedChecksums.save();
        extensionChecksums.save();
        etcChecksums.save();

        managedLibs.save();
        managedEndorsedLibs.save();
        managedExtensionLibs.save();
        managedConfigProps.save();
        managedSysProps.save();
        managedEtcs.save();

        if (restart.get()) {
            updateStatus("restarting", null);
            configProps.save();
            systemProps.save();
            System.setProperty("karaf.restart", "true");
            bundleContext.getBundle(0).stop();
            return false;
        }

        FeatureConfigInstaller configInstaller = null;
        ServiceReference configAdminServiceReference = bundleContext.getServiceReference(ConfigurationAdmin.class.getName());
        if (configAdminServiceReference != null) {
            ConfigurationAdmin configAdmin = (ConfigurationAdmin) bundleContext.getService(configAdminServiceReference);
            configInstaller = new FeatureConfigInstaller(bundleContext, configAdmin, manager);
        }

        int bundleStartTimeout = Constants.BUNDLE_START_TIMEOUT;
        String overriddenTimeout = properties.get(Constants.BUNDLE_START_TIMEOUT_PID_KEY);
        try{
            if(overriddenTimeout != null)
                bundleStartTimeout = Integer.parseInt(overriddenTimeout);
        }catch(Exception e){
            LOGGER.warn("Failed to set {} value: [{}], applying default value: {}", Constants.BUNDLE_START_TIMEOUT_PID_KEY, overriddenTimeout, Constants.BUNDLE_START_TIMEOUT);
        }
        Agent agent = new Agent(
                bundleContext.getBundle(),
                systemBundleContext,
                manager,
                configInstaller,
                null,
                DEFAULT_FEATURE_RESOLUTION_RANGE,
                DEFAULT_BUNDLE_UPDATE_RANGE,
                DEFAULT_UPDATE_SNAPSHOTS,
                bundleContext.getDataFile(STATE_FILE),
                bundleStartTimeout
        ) {
            @Override
            public void updateStatus(String status) {
                DeploymentAgent.this.updateStatus(status, null, false);
            }

            @Override
            public void updateStatus(String status, boolean force) {
                DeploymentAgent.this.updateStatus(status, null, force);
            }

            @Override
            protected void saveState(State newState) throws IOException {
                super.saveState(newState);
                DeploymentAgent.this.state.replace(newState);
            }

            @Override
            protected void provisionList(Set<Resource> resources) {
                DeploymentAgent.this.provisionList = resources;
            }

            @Override
            protected boolean done(boolean agentStarted, List<String> urls) {
                if (agentStarted) {
                    // let's do patch-management "last touch" only if new agent wasn't started.
                    return true;
                }
                // agent finished provisioning, we can call back to low level patch management
                ServiceReference<PatchManagement> srPm = systemBundleContext.getServiceReference(PatchManagement.class);
                ServiceReference<FabricService> srFs = systemBundleContext.getServiceReference(FabricService.class);
                if (srPm != null && srFs != null) {
                    PatchManagement pm = systemBundleContext.getService(srPm);
                    FabricService fs = systemBundleContext.getService(srFs);
                    if (pm != null && fs != null) {
                        LOGGER.info("Validating baseline information");
                        this.updateStatus("validating baseline information", true);
                        Profile profile = fs.getCurrentContainer().getOverlayProfile();
                        Map<String, String> versions = profile.getConfiguration("io.fabric8.version");
                        File localRepository = resolver.getLocalRepository();
                        if (pm.alignTo(versions, urls, localRepository, new Runnable() {
                            @Override
                            public void run() {
                                ServiceReference<FabricPatchService> srFps = systemBundleContext.getServiceReference(FabricPatchService.class);
                                if (srFps != null) {
                                    FabricPatchService fps = systemBundleContext.getService(srFps);
                                    if (fps != null) {
                                        try {
                                            fps.synchronize();
                                        } catch (Exception e) {
                                            LOGGER.error(e.getMessage(), e);
                                        }
                                    }
                                }
                            }
                        })) {
                            this.updateStatus("requires full restart", true);
                            // let's reuse the same flag
                            restart.set(true);
                            return false;
                        }

                        if (handleRestartJvmFlag(fs, profile, restart)) {
                            return false;
                        }

                    }
                }
                return true;
            }
        };
        agent.setDeploymentAgentId(deploymentAgentId);
        agent.provision(
                getPrefixedProperties(properties, "repository."),
                getPrefixedProperties(properties, "feature."),
                getPrefixedProperties(properties, "bundle."),
                getPrefixedProperties(properties, "req."),
                getPrefixedProperties(properties, "override."),
                getPrefixedProperties(properties, "optional."),
                getMetadata(properties, "metadata#")
        );
        if (restart.get()) {
            // prevent updating status to "success"
            return false;
        }
        return true;
    }

    /**
     * Adds support for a directive to force a restart upon the first assignment of a specific profile to a container.
     * It creates an entry in zk so that a subsequent modification to the same profile, will not trigger a jvm restart.
     * The behavior is useful for situation when a profile provision .jars in lib/ folder, that are picked up only at
     * jvm boot time.
     *
     * @param fs
     * @param profile
     * @param restart
     * @return
     */
    protected boolean handleRestartJvmFlag(FabricService fs, Profile profile, AtomicBoolean restart) {
        boolean result = false;
        List<String> profilesRequiringRestart = new ArrayList<>();
        ServiceReference<CuratorFramework> curatorServiceReference = systemBundleContext.getServiceReference(CuratorFramework.class);
        if (curatorServiceReference != null) {
            CuratorFramework curator = systemBundleContext.getService(curatorServiceReference);

            // check for jvm restart requests
            Map<String, String> agentProperties = profile.getConfiguration("io.fabric8.agent");
            Map<String, String> jvmRestartEntries = new HashMap<>();
            for(String key : agentProperties.keySet()){
                if(key.startsWith("io.fabric8.agent.forceOneTimeJVMRestart")){
                    jvmRestartEntries.put(key, agentProperties.get(key));
                    LOGGER.info("Found a profile carrying a one-time JVM restart request: {}", key);
                }
            }

            // clean old entries
            String basePath = ZkPath.CONTAINER_PROVISION_RESTART.getPath(fs.getCurrentContainerName());

            try {
                if(ZooKeeperUtils.exists(curator, basePath) != null ){
                    List<String> zkPaths = ZooKeeperUtils.getAllChildren(curator, ZkPath.CONTAINER_PROVISION_RESTART.getPath(fs.getCurrentContainerName()));
                    List<String> activeProfiles = fs.getCurrentContainer().getProfileIds();
                    for(String zkPath : zkPaths){
                        String[] split = zkPath.split("/");
                        String prof = split[split.length -1];
                        if(!activeProfiles.contains(prof)){
                            LOGGER.info("Deleting old JVM restart request status: {}", zkPath);
                            ZooKeeperUtils.delete(curator, zkPath);
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Unable to check ZK connection", e);
            }

            for(String key : jvmRestartEntries.keySet()){
                String[] split = key.split("\\.");
                String profileForcingRestart = split[split.length-1];

                // check container-profile-znode
                // if it was already in zk for the current container, do nothing

                try {
                    String zkPath = ZkPath.CONTAINER_PROVISION_RESTART_PROFILES.getPath(fs.getCurrentContainerName(), profileForcingRestart);
                    Stat exists = exists(curator, zkPath);
                    if(exists == null){
                        ZooKeeperUtils.create(curator, zkPath);
                        profilesRequiringRestart.add(profileForcingRestart);
                        result = true;
                    }
                } catch (Exception e) {
                    LOGGER.error("Unable to check ZK connection", e);
                }
            }
        }
        if(result){
            System.setProperty("karaf.restart.jvm", "true");
            restart.set(true);
            LOGGER.warn("Profiles {} scheduled a JVM restart request. Automated JVM restart support is not universally available. If your jvm doesn't support it you are required to manually restart the container that has just been assigned the profile.", profilesRequiringRestart);
            try {
                bundleContext.getBundle(0).stop();
            } catch (BundleException e) {
                LOGGER.error("Error when forcing a JVM restart", e);
            }
        }
        return result;
    }

    public static Set<String> getPrefixedProperties(Map<String, String> properties, String prefix) {
        Set<String> result = new HashSet<>();
        for (String key : properties.keySet()) {
            if (key.startsWith(prefix)) {
                String url = properties.get(key);
                if (url == null || url.length() == 0) {
                    url = key.substring(prefix.length());
                }
                if (url.length() > 0) {
                    result.add(url);
                }
            }
        }
        return result;
    }

    public static Map<String, Map<VersionRange, Map<String, String>>> getMetadata(Map<String, String> properties, String prefix) {
        Map<String, Map<VersionRange, Map<String, String>>> result = new HashMap<>();
        for (String key : properties.keySet()) {
            if (key.startsWith(prefix)) {
                String val = properties.get(key);
                key = key.substring(prefix.length());
                String[] parts = key.split("#");
                if (parts.length == 3) {
                    Map<VersionRange, Map<String, String>> ranges = result.get(parts[0]);
                    if (ranges == null) {
                        ranges = new HashMap<>();
                        result.put(parts[0], ranges);
                    }
                    String version = parts[1];
                    if (!version.startsWith("[") && !version.startsWith("(")) {
                        version = Macro.transform("${range;[==,=+)}", version);
                    }
                    VersionRange range = new VersionRange(version);
                    Map<String, String> hdrs = ranges.get(range);
                    if (hdrs == null) {
                        hdrs = new HashMap<>();
                        ranges.put(range, hdrs);
                    }
                    hdrs.put(parts[2], val);
                }
            }
        }
        return result;
    }

    protected ScheduledExecutorService getDownloadExecutor() {
        return downloadExecutor;
    }

}
