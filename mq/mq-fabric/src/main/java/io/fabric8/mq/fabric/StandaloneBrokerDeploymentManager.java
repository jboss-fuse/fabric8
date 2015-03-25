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
package io.fabric8.mq.fabric;

import java.util.Map;
import java.util.Properties;

import io.fabric8.api.FabricService;
import io.fabric8.api.scr.ValidatingReference;
import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.url.URLStreamHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(
    name = "io.fabric8.mq.fabric.standalone-deployment-manager",
    label = "Fabric8 ActiveMQ Standalone Deployment Manager",
    policy = ConfigurationPolicy.IGNORE
)
@org.apache.felix.scr.annotations.Properties({ @Property(name = "server.kind", value = "standalone") })
@Service(BrokerDeploymentManager.class)
public class StandaloneBrokerDeploymentManager implements BrokerDeploymentManager {

    public static final Logger LOG = LoggerFactory.getLogger(StandaloneBrokerDeploymentManager.class);
    ActiveMQServiceFactory serviceFactory;

    @Activate
    void activate(ComponentContext context) throws Exception {
        serviceFactory = new ActiveMQServiceFactory();
        serviceFactory.bundleContext = context.getBundleContext();
    }

    @Deactivate
    void deactivate() throws InterruptedException {
        serviceFactory.destroy();
        serviceFactory = null;
    }

    @Override
    public void updated(String pid, Properties properties) throws ConfigurationException {
        serviceFactory.updated(pid, properties);
    }

    @Override
    public void deleted(String pid) {
        serviceFactory.deleted(pid);
    }
}
