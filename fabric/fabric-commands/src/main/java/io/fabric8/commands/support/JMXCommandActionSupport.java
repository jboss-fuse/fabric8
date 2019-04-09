/*
 *  Copyright 2005-2017 Red Hat, Inc.
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
package io.fabric8.commands.support;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.fabric8.api.Container;
import io.fabric8.api.FabricService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.zookeeper.ZkPath;
import org.apache.curator.framework.CuratorFramework;
import org.apache.karaf.shell.console.AbstractAction;

public abstract class JMXCommandActionSupport extends AbstractAction {

    protected final FabricService fabricService;
    protected final CuratorFramework curator;
    protected final RuntimeProperties runtimeProperties;

    public JMXCommandActionSupport(FabricService fabricService, CuratorFramework curator, RuntimeProperties runtimeProperties) {
        this.fabricService = fabricService;
        this.curator = curator;
        this.runtimeProperties = runtimeProperties;
    }

    @Override
    protected Object doExecute() throws Exception {
        return null;
    }

    /**
     * Even if we could make response queues persistent, I think not doing it is better idea. Each command
     * cleans after itself (and previous, failed/timed out commands)
     */
    protected void cleanResponses() {
        for (Container container : fabricService.getContainers()) {
            try {
                String path = ZkPath.COMMANDS_RESPONSES.getPath(container.getId());
                List<String> responses = curator.getChildren().forPath(path);
                for (String r : responses) {
                    curator.delete().forPath(path + "/" + r);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Helper Object -&gt; JSON mapper for Karaf commands.
     * @param jmxRequest
     * @return
     * @throws JsonProcessingException
     */
    protected String map(Object jmxRequest) throws JsonProcessingException {
        ObjectMapper mapper = getObjectMapper();
        try {
            return mapper.writeValueAsString(jmxRequest);
        } finally {
            mapper.getTypeFactory().clearCache();
        }
    }

    protected ObjectMapper getObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setTypeFactory(TypeFactory.defaultInstance().withClassLoader(getClass().getClassLoader()));
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        return mapper;
    }

}
