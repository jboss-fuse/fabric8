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

import java.util.Map;
import java.util.Properties;

import io.fabric8.api.scr.ValidatingReference;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.url.URLStreamHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(
    name = "io.fabric8.mq.fabric.standalone.server",
    label = "Fabric8 ActiveMQ Standalone Deployment",
    configurationFactory=true,
    policy = ConfigurationPolicy.REQUIRE
)
public class StandaloneBrokerDeployment {

    public static final Logger LOG = LoggerFactory.getLogger(StandaloneBrokerDeployment.class);

    @Reference(referenceInterface = BrokerDeploymentManager.class, target = "(&(objectClass=io.fabric8.mq.fabric.BrokerDeploymentManager)(server.kind=standalone))")
    private final ValidatingReference<BrokerDeploymentManager> brokerDeploymentManager = new ValidatingReference<>();

    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configurationAdmin = new ValidatingReference<>();

    private String pid;

    @Activate
    void activate(Map<String, ?> configuration) throws Exception {
        Properties properties = toProperties(configuration);

        // Make sure the original config we are linked to still exists.
        if( !BrokerDeployment.LOAD_TS.equals(properties.getProperty("mq.fabric.server.ts") ) ) {
            // Our pid is now stale.
            Configuration ourConfig = getConfigurationAdmin().getConfiguration(properties.getProperty("service.pid"));
            ourConfig.delete();
        } else {
            pid = properties.getProperty("service.pid");
            getBrokerDeploymentManager().updated(pid, properties);
        }
    }

    @Modified
    void modified(Map<String, ?> configuration) throws Exception {
        if( pid !=null ) {
            Properties properties = toProperties(configuration);
            getBrokerDeploymentManager().updated(pid, properties);
        }
    }

    @Deactivate
    void deactivate() {
        if( pid !=null ) {
            getBrokerDeploymentManager().deleted(pid);
        }
    }

    protected BrokerDeploymentManager getBrokerDeploymentManager() {
        return brokerDeploymentManager.get();
    }

    protected ConfigurationAdmin getConfigurationAdmin() {
        return configurationAdmin.get();
    }

    void bindBrokerDeploymentManager(BrokerDeploymentManager service) {
        this.brokerDeploymentManager.bind(service);
    }
    void unbindBrokerDeploymentManager(BrokerDeploymentManager service) {
        this.brokerDeploymentManager.unbind(service);
    }

    private static Properties toProperties(Map<?, ?> properties) {
        Properties result = new Properties();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            result.put(entry.getKey().toString(), entry.getValue() != null ? entry.getValue().toString() : "");
        }
        return result;
    }

    void bindConfigurationAdmin(ConfigurationAdmin service) {
        this.configurationAdmin.bind(service);
    }
    void unbindConfigurationAdmin(ConfigurationAdmin service) {
        this.configurationAdmin.unbind(service);
    }




}
