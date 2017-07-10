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

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.fabric8.api.Container;
import io.fabric8.api.FabricService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.commands.JMXResult;
import io.fabric8.zookeeper.ZkPath;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.PublicStringSerializer;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Option;
import org.apache.karaf.shell.console.AbstractAction;

import static io.fabric8.utils.FabricValidations.validateContainerName;

public abstract class JMXCommandActionSupport extends AbstractAction {

    @Option(name = "-a", aliases = { "--all" }, description = "Check status of all containers", required = false, multiValued = false)
    protected boolean allContainers = false;

    @Option(name = "-t", aliases = { "--timeout" }, description = "Timeout used when waiting for response(s)", required = false, multiValued = false)
    protected long timeout = 5000L;

    @Argument(index = 0, name = "container", description = "The container names", required = false, multiValued = true)
    protected List<String> containers = null;

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
        Collection<String> names = new LinkedList<>();
        if (allContainers) {
            if (containers != null && containers.size() > 0) {
                System.out.println("Container names are ignored when using \"--all\" option.");
            }
            Container[] all = CommandUtils.sortContainers(fabricService.getContainers());
            for (Container c : all) {
                names.add(c.getId());
            }
        } else {
            names.addAll(ContainerGlobSupport.expandGlobNames(fabricService, containers));
        }

        List<String> validContainerNames = new LinkedList<>();
        for (String name: names) {
            try {
                validateContainerName(name);
            } catch (IllegalArgumentException e) {
                System.err.println("Skipping illegal container name \"" + name + "\"");
                continue;
            }
            validContainerNames.add(name);
        }

        beforeEachContainer(validContainerNames);

        for (String name: validContainerNames) {
            // for each container we have to pass JMXRequest
            String path = ZkPath.COMMANDS_REQUESTS_QUEUE.getPath(name);

            performContainerAction(path, name);
        }

        afterEachContainer(validContainerNames);

        cleanResponses();

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
     * Action to be performed before sending commands to selected containers
     * @param names
     */
    protected void beforeEachContainer(Collection<String> names) throws Exception { }

    /**
     * Perform given action on named (valid) container
     * @param queuePath
     * @param containerName
     */
    protected abstract void performContainerAction(String queuePath, String containerName) throws Exception;

    /**
     * Action to be performed after sending commands to selected containers
     * @param names
     */
    protected void afterEachContainer(Collection<String> names) throws Exception { }

    /**
     * Helper Object -&gt; JSON mapper for Karaf commands.
     * @param jmxRequest
     * @return
     * @throws JsonProcessingException
     */
    protected String map(Object jmxRequest) throws JsonProcessingException {
        return getObjectMapper().writeValueAsString(jmxRequest);
    }

    protected List<JMXResult> asResults(String path, List<String> responses, Class<?> resultClass) throws Exception {
        ObjectMapper mapper = getObjectMapper();
        List<JMXResult> results = new LinkedList<>();
        for (String responsePath : responses) {
            byte[] bytes = curator.getData().forPath(path + "/" + responsePath);
            String response = PublicStringSerializer.deserialize(bytes);
            JMXResult result = mapper.readValue(response, JMXResult.class);
            if (result.getResponse() instanceof String) {
                try {
                    result.setResponse(mapper.readValue((String)result.getResponse(), resultClass));
                } catch (JsonMappingException | IllegalArgumentException ignore) {
                    continue;
                }
            }
            results.add(result);
        }
        mapper.getTypeFactory().clearCache();
        return results;
    }

    private ObjectMapper getObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setTypeFactory(TypeFactory.defaultInstance().withClassLoader(getClass().getClassLoader()));
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        return mapper;
    }

}
