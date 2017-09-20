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

    Configuration config;

    @Activate
    void activate(Map<String, ?> configuration) throws Exception {
        boolean standalone = "true".equalsIgnoreCase((String) configuration.get("standalone"));

        String factoryPid = standalone ?
                "io.fabric8.mq.fabric.standalone.server" :
                "io.fabric8.mq.fabric.clustered.server";

        config = configurationAdmin.get().createFactoryConfiguration(factoryPid, null);
        config.update(toDictionary(configuration));

    }

    private Dictionary<String, ?> toDictionary(Map<String, ?> configuration) {
        Hashtable<String, Object> properties = new Hashtable<String, Object>(configuration);
        properties.remove("component.id");
        properties.remove("component.name");
        properties.remove("service.factoryPid");
        String pid = (String) properties.remove("service.pid");
        properties.put("mq.fabric.server.pid", pid);
        properties.put("mq.fabric.server.ts", LOAD_TS);
        return properties;
    }

    @Modified
    void modified(Map<String, ?> configuration) throws Exception {
        if( config!=null ) {
           try
           {
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

    void bindConfigurationAdmin(ConfigurationAdmin service) {
        this.configurationAdmin.bind(service);
    }
    void unbindConfigurationAdmin(ConfigurationAdmin service) {
        this.configurationAdmin.unbind(service);
    }


}
