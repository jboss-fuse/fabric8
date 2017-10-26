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
package io.fabric8.mq.fabric;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;

import io.fabric8.api.scr.ValidatingReference;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Reference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Main SCR component responsible for broker management (standalone or clustered).</p>
 * <p>This is SCR component with {@link Component#configurationFactory()} set to <code>true</code>. So each PID from
 * <code>io.fabric8.mq.fabric.server</code> <em>factory PID</em> will have corresponding SCR component instance.</p>
 * <p>The responsibility of this SCR is to create either <code>io.fabric8.mq.fabric.standalone.server</code> or
 * <code>io.fabric8.mq.fabric.clustered.server</code> <em>factory PID</em> and create <strong>single</strong>
 * PID out of it. The PID is removed when this instance of SCR component is deactivated. This means that when container
 * is stopped, there are <strong>no standalone/clustered</strong> PIDs available - they'll be created again. If the
 * container is not stopped gracefully, another instance of standalone/clustered factory PID will be created, but
 * only recent broker will be active, old standalone/clustered PID will be deleted (see
 * {@link StandaloneBrokerDeployment#activate(Map)}).</p>
 */
@Component(
    name = "io.fabric8.mq.fabric.server",
    label = "Fabric8 ActiveMQ Deployment",
    configurationFactory=true,
    policy = ConfigurationPolicy.REQUIRE
)
public class BrokerDeployment {

    public static final String LOAD_TS = Long.toHexString(System.currentTimeMillis());
    public static final Logger LOG = LoggerFactory.getLogger(BrokerDeployment.class);

    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configurationAdmin = new ValidatingReference<>();

    /**
     * Each instance of this SCR component manages corresponding PID from one of
     * [<code>io.fabric8.mq.fabric.standalone.server</code>, <code>io.fabric8.mq.fabric.clustered.server</code>]
     * factory PIDs.
     */
    Configuration config;

    @Activate
    void activate(Map<String, ?> configuration) throws Exception {
        boolean fabricManaged = "true".equalsIgnoreCase((String) configuration.get("fabric.managed"));
        if (fabricManaged) {
            LOG.info("AMQ broker configuration is managed by fabric, skipping configuration.");
            return;
        }

        if (!isConfiguredFromProfile(configuration)) {
            // it means we're activated using file from ${karaf.etc} directory
            boolean connectedToFabric = System.getProperty("zookeeper.url") != null && !"".equals(System.getProperty("zookeeper.url").trim());
            if (!connectedToFabric) {
                Configuration[] configurations = configurationAdmin.get().listConfigurations("(service.pid=io.fabric8.zookeeper)");
                if (configurations != null) {
                    for (Configuration config : configurations) {
                        if (config.getProperties() != null && config.getProperties().get("zookeeper.url") != null
                                && !"".equals(((String)config.getProperties().get("zookeeper.url")).trim())) {
                            connectedToFabric = true;
                            break;
                        }
                    }
                }
            }
            if (connectedToFabric) {
                LOG.info("AMQ broker configuration is managed by fabric, skipping configuration.");
                return;
            }
        }

        boolean standalone = "true".equalsIgnoreCase((String) configuration.get("standalone"));

        String factoryPid = standalone ?
                "io.fabric8.mq.fabric.standalone.server" :
                "io.fabric8.mq.fabric.clustered.server";

        config = configurationAdmin.get().createFactoryConfiguration(factoryPid, null);
        config.update(toDictionary(configuration));

    }

    /**
     * Adjust configuration coming from configadmin for the needs of {@link StandaloneBrokerDeployment} or
     * {@link ClusteredBrokerDeployment}
     * @param configuration
     * @return
     */
    private Dictionary<String, ?> toDictionary(Map<String, ?> configuration) {
        Hashtable<String, Object> properties = new Hashtable<String, Object>(configuration);

        // we don't need configadmin/scr properties - configadmin ones will be added anyway automatically
        properties.remove("component.id");
        properties.remove("component.name");
        properties.remove("service.factoryPid");

        // we'll have new PID (io.fabric8.mq.fabric.server -> io.fabric8.mq.fabric.[standalone|clustered].server
        String pid = (String) properties.remove("service.pid");

        // but we'll remember original PID
        properties.put("mq.fabric.server.pid", pid);

        // and "current" configuration indicated by timestamp. When container is not shut down cleanly, we'll be able
        // to check if the configuration is an old one (failed to be deleted in this.deactivate())
        properties.put("mq.fabric.server.ts", LOAD_TS);

        // This SCR component manages given (standalone or clustered) PID, and not FabricConfigAdminBridge, so we
        // don't need fabric.zookeeper.pid in io.fabric8.mq.fabric.[standalone|clustered].server
        // we'll still have it in io.fabric8.mq.fabric.server, so FCAB will update it on the basis of new special
        // profile with "fabric.managed = true" property
        properties.remove("fabric.zookeeper.pid");

        // we have to remove fileinstall reference, so FCAB updates won't be propagated back to
        // etc/io.fabric8.mq.fabric.server-broker.cfg file When updating
        // io.fabric8.mq.fabric.[standalone|clustered].server PIDs. However update to
        // io.fabric8.mq.fabric.server PID *will be* propaged
        properties.remove("felix.fileinstall.filename");

        return properties;
    }

    @Modified
    void modified(Map<String, ?> configuration) throws Exception {
        if( config!=null ) {
           try
           {
               boolean fabricManaged = "true".equalsIgnoreCase((String) configuration.get("fabric.managed"));
               if (fabricManaged) {
                   LOG.info("AMQ broker configuration is managed by fabric, skipping configuration.");
                   config.delete();
                   return;
               }
              config.update(toDictionary(configuration));
           }
           catch (IllegalStateException e)
           {
              // Typically a 'java.lang.IllegalStateException: Configuration xxxx deleted' error
              activate(configuration);
           }
        }
    }

    @Deactivate
    void deactivate() throws IOException {
        if( config!=null ) {
           try
           {
              config.delete();
           }
           catch (IllegalStateException ignore) {
           }
        }
    }

    /**
     * Checks whether the configuration was creted from fabric profile or from
     * <code>${karaf.etc}/*.cfg</code> file
     * @param configuration
     * @return
     */
    private boolean isConfiguredFromProfile(Map<String, ?> configuration) {
        String etcFileName = (String) configuration.get("felix.fileinstall.filename");
        return etcFileName == null || "".equals(etcFileName.trim());
    }

    void bindConfigurationAdmin(ConfigurationAdmin service) {
        this.configurationAdmin.bind(service);
    }
    void unbindConfigurationAdmin(ConfigurationAdmin service) {
        this.configurationAdmin.unbind(service);
    }


}
