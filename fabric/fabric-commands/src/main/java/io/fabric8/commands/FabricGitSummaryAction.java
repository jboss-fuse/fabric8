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
package io.fabric8.commands;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import io.fabric8.api.Container;
import io.fabric8.api.FabricService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.commands.JMXRequest;
import io.fabric8.commands.support.CommandUtils;
import io.fabric8.commands.support.ContainerGlobSupport;
import io.fabric8.commands.support.JMXCommandActionSupport;
import io.fabric8.zookeeper.ZkPath;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.PublicStringSerializer;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.zookeeper.CreateMode;

import static io.fabric8.utils.FabricValidations.validateContainerName;

@Command(name = FabricGitSummary.FUNCTION_VALUE, scope = FabricGitSummary.SCOPE_VALUE, description = FabricGitSummary.DESCRIPTION)
public class FabricGitSummaryAction extends JMXCommandActionSupport {

    @Option(name = "-a", aliases = { "--all" }, description = "Check status of all containers", required = false, multiValued = false)
    private boolean allContainers = false;

    @Option(name = "-t", aliases = { "--timeout" }, description = "Timeout used when waiting for response(s)", required = false, multiValued = false)
    private long timeout = 5000L;

    @Argument(index = 0, name = "container", description = "The container names", required = false, multiValued = true)
    protected List<String> containers = null;

    private final FabricService fabricService;
    private final CuratorFramework curator;
    private final RuntimeProperties runtimeProperties;

    FabricGitSummaryAction(FabricService fabricService, CuratorFramework curator, RuntimeProperties runtimeProperties) {
        this.fabricService = fabricService;
        this.curator = curator;
        this.runtimeProperties = runtimeProperties;
    }

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
        for (String name: names) {
            try {
                validateContainerName(name);
            } catch (IllegalArgumentException e) {
                System.err.println("Skipping illegal container name \"" + name + "\"");
            }

            // for each container we have to pass JMXRequest
            String path = ZkPath.COMMANDS_REQUESTS_QUEUE.getPath(name);
            // hand-made org.apache.curator.framework.recipes.queue.DistributedQueue.put()
            String command = map(new JMXRequest().withObjectName("io.fabric8:type=Fabric").withMethod("gitVersions"));
            curator.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL).forPath(path, PublicStringSerializer.serialize(command));
        }
        System.out.printf("Scheduled git-summary command to %d containers\n", names.size());
        return null;
    }

}
