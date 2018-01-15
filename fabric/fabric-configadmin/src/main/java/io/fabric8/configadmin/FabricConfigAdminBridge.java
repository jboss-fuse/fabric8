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
package io.fabric8.configadmin;

import io.fabric8.api.Constants;
import io.fabric8.api.Container;
import io.fabric8.api.FabricService;
import io.fabric8.api.Profile;
import io.fabric8.api.Profiles;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import io.fabric8.utils.NamedThreadFactory;
import io.fabric8.zookeeper.utils.InterpolationHelper;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.utils.properties.TypedProperties;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.url.URLStreamHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
@Component(name = "io.fabric8.configadmin.bridge", label = "Fabric8 Config Admin Bridge", metatype = false)
public final class FabricConfigAdminBridge extends AbstractComponent implements Runnable {

    public static final String FABRIC_ZOOKEEPER_PID = "fabric.zookeeper.pid";
    public static final String FELIX_FILE_INSTALL_FILE_NAME = "felix.fileinstall.filename";
    public static final String FELIX_FILE_INSTALL_DIR = "felix.fileinstall.dir";
    /**
     * Configuration property that indicates if old configuration should be preserved.
     * By default, previous configuration is discarded and new is used instead.
     * When setting this property to <code>true</code>, new values override existing ones
     * and the old values are kept unchanged.
     */
    public static final String FABRIC_CONFIG_MERGE = "fabric.config.merge";
    public static final String LAST_MODIFIED = "lastModified";

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricConfigAdminBridge.class);

    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configAdmin = new ValidatingReference<ConfigurationAdmin>();
    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();
    @Reference(referenceInterface = URLStreamHandlerService.class, target = "(url.handler.protocol=profile)")
    private final ValidatingReference<URLStreamHandlerService> urlHandler = new ValidatingReference<URLStreamHandlerService>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("fabric-configadmin"));

    private File fileinstallDir;

    @Activate
    void activate(BundleContext context) {
        fabricService.get().trackConfiguration(this);
        activateComponent();
        String fileinstallDirName = context.getProperty(FELIX_FILE_INSTALL_DIR);
        if (fileinstallDirName != null) {
            File dir = new File(fileinstallDirName);
            if (dir.isDirectory() && dir.canWrite()) {
                fileinstallDir = dir;
            }
        }
        submitUpdateJob();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
        fabricService.get().untrackConfiguration(this);
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            // Ignore
        }
        executor.shutdownNow();
    }

    @Override
    public void run() {
        submitUpdateJob();
    }

    private void submitUpdateJob() {
        try {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    if (isValid()) {
                        try {
                            updateInternal();
                        } catch (Throwable th) {
                            if (isValid()) {
                                LOGGER.warn("Exception when tracking configurations. This exception will be ignored.", th);
                            } else {
                                LOGGER.debug("Exception when tracking configurations. This exception will be ignored because services have been unbound in the mean time.", th);
                            }
                        }
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            if (!executor.isShutdown()) {
                throw e;
            } else {
                LOGGER.debug("Update task wasn't submitted, component is no longer active");
            }
        }
    }

    /**
     * Method scheduled to run in separate thread - so be careful, as we may be running in deactivated SCR
     * component.
     * @throws Exception
     */
    private synchronized void updateInternal() throws Exception {
        try {

            Container currentContainer = fabricService.get().getCurrentContainer();
            if (currentContainer == null) {
                LOGGER.warn("No current container yet so cannot update!");
                return;
            }
            Profile overlayProfile = null;
            try {
                overlayProfile = currentContainer.getOverlayProfile();
            } catch (RuntimeException e) {
                LOGGER.warn("No profile data yet so cannot update!");
                return;
            }

            Profile effectiveProfile = Profiles.getEffectiveProfile(fabricService.get(), overlayProfile);

            Map<String, Map<String, String>> configurations = effectiveProfile.getConfigurations();
            List<Configuration> zkConfigs = asList(configAdmin.get().listConfigurations("(" + FABRIC_ZOOKEEPER_PID + "=*)"));

            // FABRIC-803: the agent may use the configuration provided by features definition if not managed
            //   by fabric.  However, in order for this to work, we need to make sure managed configurations
            //   are all registered before the agent kicks in.  Hence, the agent configuration is updated
            //   after all other configurations.

            // Process all configurations but agent
            for (String pid : configurations.keySet()) {
                if (!pid.equals(Constants.AGENT_PID)) {
                    Hashtable<String, Object> c = new Hashtable<String, Object>(configurations.get(pid));
                    if (!updateConfig(zkConfigs, pid, c)) {
                        return;
                    }
                }
            }
            // Process agent configuration last
            for (String pid : configurations.keySet()) {
                if (pid.equals(Constants.AGENT_PID)) {
                    Hashtable<String, Object> c = new Hashtable<String, Object>(configurations.get(pid));
                    c.put(Profile.HASH, String.valueOf(effectiveProfile.getProfileHash()));
                    if (!updateConfig(zkConfigs, pid, c)) {
                        return;
                    }
                }
            }
            for (Configuration config : zkConfigs) {
                LOGGER.info("Deleting configuration {}", config.getPid());
                fabricService.get().getPortService().unregisterPort(fabricService.get().getCurrentContainer(), config.getPid());
                if (!isValid()) {
                    return;
                }
                config.delete();
            }

            // end of update
            Configuration fcab = configAdmin.get().getConfiguration(Constants.CONFIGADMIN_BRIDGE_PID, null);
            Hashtable<String, String> props = new Hashtable<>();
            props.put("lastUpdate", Long.toString(new Date().getTime()));
            fcab.update(props);
        } catch (IllegalStateException e){
            handleException(e);
        }
    }

    /**
     * Update CM configuration if there's a change. First check if {@link FabricConfigAdminBridge} is still valid,
     * as we don't want to update configs that may lead to invocation of SCR components that are no longer valid
     * (like {@link io.fabric8.service.ProfileUrlHandler} or {@link FabricService})
     * @param configs
     * @param pid
     * @param c
     * @return
     * @throws Exception
     */
    private boolean updateConfig(List<Configuration> configs, String pid, Hashtable<String, Object> c) throws Exception {
        if (!isValid()) {
            return false;
        }
        String p[] = parsePid(pid);
        //Get the configuration by fabric zookeeper pid, pid and factory pid.
        Configuration config = getConfiguration(configAdmin.get(), pid, p[0], p[1]);
        configs.remove(config);
        Dictionary<String, Object> props = config.getProperties();
        Hashtable<String, Object> old = props != null ? new Hashtable<String, Object>() : null;
        Object felix_file_install_name = null;
        if (old != null) {
            for (Enumeration<String> e = props.keys(); e.hasMoreElements();) {
                String key = e.nextElement();
                Object val = props.get(key);
                old.put(key, val);
            }
            old.remove(FABRIC_ZOOKEEPER_PID);
            old.remove(org.osgi.framework.Constants.SERVICE_PID);
            old.remove(ConfigurationAdmin.SERVICE_FACTORYPID);
            // stash a property to avoid false positive updates related to RBAC config files, restores it at the end of the method
            felix_file_install_name = old.remove(FELIX_FILE_INSTALL_FILE_NAME);
        }
        if (!c.equals(old)) {
            LOGGER.info("Updating configuration {}", config.getPid());
            c.put(FABRIC_ZOOKEEPER_PID, pid);
            // FABRIC-1078. Do *not* set the bundle location to null
            // causes org.apache.felix.cm.impl.ConfigurationManager.locationChanged() to be fired and:
            // - one of the listeners (SCR) tries to getConfiguration(pid)
            // - bundle location is checked to be null
            // - bundle location is set
            // - org.apache.felix.cm.impl.ConfigurationBase.storeSilently() is invoked
            // and all of this *after* the below config.update(), only with *old* values
//            if (config.getBundleLocation() != null) {
//                config.setBundleLocation(null);
//            }
            if ("true".equals(c.get(FABRIC_CONFIG_MERGE)) && old != null) {
                // CHECK: if we remove FABRIC_CONFIG_MERGE property, profile update
                // would remove the original properties - only profile-defined will be used
                //c.remove(FABRIC_CONFIG_MERGE);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("");
                }
                old.putAll(c);
                c = old;
            }

            if (felix_file_install_name != null && !c.containsKey(FELIX_FILE_INSTALL_FILE_NAME)) {
                c.put(FELIX_FILE_INSTALL_FILE_NAME, felix_file_install_name);
            }

            performUpdate(config, c);
        } else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Ignoring configuration {} (no changes)", config.getPid());
            }
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> asList(T... a) {
        List<T> l = new ArrayList<T>();
        if (a != null) {
            Collections.addAll(l, a);
        }
        return l;
    }

    /**
     * Splits a pid into service and factory pid.
     *
     * @param pid The pid to parse.
     * @return An arrays which contains the pid[0] the pid and pid[1] the factory pid if applicable.
     */
    private String[] parsePid(String pid) {
        int n = pid.indexOf('-');
        if (n > 0) {
            String factoryPid = pid.substring(n + 1);
            pid = pid.substring(0, n);
            return new String[]{pid, factoryPid};
        } else {
            return new String[]{pid, null};
        }
    }

    /**
     * Update the configuration with all necessary activities (like fileinstall handling)
     * @param config
     * @param c
     */
    private void performUpdate(Configuration config, Hashtable<String, Object> c) throws IOException {
        // ENTESB-7584 here's where problems may arise. When configuration is tied to etc/*.cfg file
        // by felix.fileinstall.filename property, updating a configuration via configadmin will propagate
        // to fileinstall with CM_UPDATED event which will change the file.
        // if we write an unescaped placeholder to configadmin, fileinstall will write it too, BUT
        // then (e.g., during container restart) it'll read the property, RESOLVE the placeholder (most
        // probably to "") and update configuration admin (and the update will be overwritten by FCAB again)
        // so we must manually write to etc/ (when fileinstall is stopped!) to have everything in sync.
        if (fileinstallDir == null || !c.containsKey(FELIX_FILE_INSTALL_FILE_NAME)) {
            // we don't care about fileinstall at all
            config.update(c);
            return;
        }

        try {
            boolean anyEscape = InterpolationHelper.escapePropertyPlaceholders(c);
            if (!anyEscape) {
                // we follow the easy path, where we don't have to stop fileinstall
                config.update(c);
                return;
            }

            // the hard way:
            //  - update etc/pid.cfg manually with proper escaping
            //  - read file again
            //  - update configadmin - but without felix.fileinstall.filename property, so we don't
            //    propagate CM_UPDATED event again to fileinstall and back to configadmin...
            // note that saving file in etc/*.cfg will make fileinstall update the configuration
            // but there's no better way of synchronizing...

            TypedProperties props = new TypedProperties();
            File etcFile = getCfgFileFromProperty(c.get(FELIX_FILE_INSTALL_FILE_NAME));
            props.load(etcFile);
            props.putAll(c);
            props.keySet().retainAll(c.keySet());
            props.save(etcFile);
            props.clear();
            props.load(etcFile);

            props.remove(FELIX_FILE_INSTALL_FILE_NAME);
            config.update(new Hashtable<>(props));
        } catch (FileNotFoundException e) {
            LOGGER.warn("Can't load {}", c.get(FELIX_FILE_INSTALL_FILE_NAME));
        } catch (URISyntaxException e) {
            LOGGER.debug(e.getMessage(), e);
        }
    }

    private File getCfgFileFromProperty(Object val) throws URISyntaxException, MalformedURLException {
        if (val instanceof URL) {
            return new File(((URL) val).toURI());
        }
        if (val instanceof URI) {
            return new File((URI) val);
        }
        if (val instanceof String) {
            return new File(new URL((String) val).toURI());
        }
        return null;
    }

    private Configuration getConfiguration(ConfigurationAdmin configAdmin, String zooKeeperPid, String pid, String factoryPid) throws Exception {
        String filter = "(" + FABRIC_ZOOKEEPER_PID + "=" + zooKeeperPid + ")";
        Configuration[] oldConfiguration = configAdmin.listConfigurations(filter);
        if (oldConfiguration != null && oldConfiguration.length > 0) {
            return oldConfiguration[0];
        } else {
            Configuration newConfiguration;
            if (factoryPid != null) {
                newConfiguration = configAdmin.createFactoryConfiguration(pid, null);
            } else {
                newConfiguration = configAdmin.getConfiguration(pid, null);
                // 104.4.2 Dynamic Binding:
                //  A null location parameter can be used to create Configuration objects that are not yet bound.
                //  In this case, the Configuration becomes bound to a specific location the first time that it is
                //  compared to a Bundle’s location.
                //
                //  It is recommended that management agents explicitly set the location to a ? (a multi-location)
                //  to allow multiple bundles to share PIDs and not use the dynamic binding facility.
                newConfiguration.setBundleLocation("?");
            }
            return newConfiguration;
        }
    }

    protected void handleException(Throwable e) {
        if( e instanceof IllegalStateException && "Client is not started".equals(e.getMessage())) {
            LOGGER.debug("", e);
        }
        else {
            LOGGER.error("", e);
        }
    }

    void bindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.bind(service);
    }

    void unbindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.unbind(service);
    }

    void bindFabricService(FabricService fabricService) {
        this.fabricService.bind(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.unbind(fabricService);
    }

    void bindUrlHandler(URLStreamHandlerService urlHandler) {
        this.urlHandler.bind(urlHandler);
    }

    void unbindUrlHandler(URLStreamHandlerService urlHandler) {
        this.urlHandler.unbind(urlHandler);
    }

}
