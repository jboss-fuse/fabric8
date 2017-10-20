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

import io.fabric8.api.scr.ValidatingReference;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Reference;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(
    name = "io.fabric8.mq.fabric.clustered.server",
    label = "Fabric8 ActiveMQ Clustered Deployment",
    configurationFactory=true,
    policy = ConfigurationPolicy.REQUIRE
)
public class ClusteredBrokerDeployment extends StandaloneBrokerDeployment {

    public static final Logger LOG = LoggerFactory.getLogger(ClusteredBrokerDeployment.class);

    @Reference(referenceInterface = BrokerDeploymentManager.class, target = "(&(objectClass=io.fabric8.mq.fabric.BrokerDeploymentManager)(server.kind=clustered))")
    private final ValidatingReference<BrokerDeploymentManager> brokerDeploymentManager = new ValidatingReference<>();

    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configurationAdmin = new ValidatingReference<>();

    @Activate
    void activate(Map<String, ?> configuration) throws Exception {
        super.activate(configuration);
    }

    @Modified
    void modified(Map<String, ?> configuration) throws Exception {
        super.modified(configuration);
    }

    @Deactivate
    void deactivate() {
        super.deactivate();
    }

    @Override
    protected BrokerDeploymentManager getBrokerDeploymentManager() {
        return this.brokerDeploymentManager.get();
    }

    void bindBrokerDeploymentManager(BrokerDeploymentManager service) {
        this.brokerDeploymentManager.bind(service);
    }
    void unbindBrokerDeploymentManager(BrokerDeploymentManager service) {
        this.brokerDeploymentManager.unbind(service);
    }

    void bindConfigurationAdmin(ConfigurationAdmin service) {
        this.configurationAdmin.bind(service);
    }
    void unbindConfigurationAdmin(ConfigurationAdmin service) {
        this.configurationAdmin.unbind(service);
    }
    protected ConfigurationAdmin getConfigurationAdmin() {
        return configurationAdmin.get();
    }


}
