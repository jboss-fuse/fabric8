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
import org.osgi.service.component.ComponentContext;
import org.osgi.service.url.URLStreamHandlerService;

@Component(
    name = "io.fabric8.mq.fabric.clustered-deployment-manager",
    label = "Fabric8 ActiveMQ Clustered Deployment Manager",
    policy = ConfigurationPolicy.IGNORE
)
@org.apache.felix.scr.annotations.Properties({ @Property(name = "server.kind", value = "clustered") })
@Service(BrokerDeploymentManager.class)
public class ClusteredBrokerDeploymentManager extends StandaloneBrokerDeploymentManager implements BrokerDeploymentManager {

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<>();

    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curatorFramework = new ValidatingReference<>();

    @Reference(referenceInterface = URLStreamHandlerService.class, target = "(&(objectClass=org.osgi.service.url.URLStreamHandlerService)(url.handler.protocol=profile2))")
    private final ValidatingReference<URLStreamHandlerService> urlStreamHandlerService = new ValidatingReference<>();

    @Activate
    void activate(ComponentContext context) throws Exception {
        super.activate(context);
        serviceFactory.curator = curatorFramework.get();
        serviceFactory.fabricService = fabricService.get();
    }

    @Deactivate
    void deactivate() throws InterruptedException {
        super.deactivate();
    }

    void bindFabricService(FabricService service) {
        this.fabricService.bind(service);
    }
    void unbindFabricService(FabricService service) {
        this.fabricService.unbind(service);
    }
    void bindCuratorFramework(CuratorFramework service) {
        this.curatorFramework.bind(service);
    }
    void unbindCuratorFramework(CuratorFramework service) {
        this.curatorFramework.unbind(service);
    }
    void bindUrlStreamHandlerService(URLStreamHandlerService service) {
        this.urlStreamHandlerService.bind(service);
    }
    void unbindUrlStreamHandlerService(URLStreamHandlerService service) {
        this.urlStreamHandlerService.unbind(service);
    }



}
