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

import java.util.LinkedList;
import java.util.List;

import io.fabric8.api.Container;
import io.fabric8.api.FabricService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.commands.JMXRequest;
import io.fabric8.boot.commands.support.FabricCommand;
import io.fabric8.commands.support.JMXCommandActionSupport;
import io.fabric8.utils.shell.ShellUtils;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.curator.CuratorFrameworkLocator;
import org.apache.curator.framework.recipes.queue.PublicStringSerializer;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.zookeeper.CreateMode;

@Command(name = Leave.FUNCTION_VALUE, scope = Leave.SCOPE_VALUE, description = Leave.DESCRIPTION)
final class LeaveAction extends JMXCommandActionSupport {

    @Option(name = "-f", aliases = { "--force" }, multiValued = false, required = false, description = "Force the execution of the command regardless of the known state of the container")
    protected boolean force = false;

    @Argument(required = false, index = 0, multiValued = false, description = "Container name to disconnect. By default, current container leaves fabric.")
    private String containerName;

    LeaveAction(RuntimeProperties runtimeProperties, FabricService fabricService) {
        super(fabricService, CuratorFrameworkLocator.getCuratorFramework(), runtimeProperties);
    }

    @Override
    protected Object doExecute() throws Exception {
        if (containerName == null || "".equals(containerName)) {
            // leaving current container
            containerName = runtimeProperties.getRuntimeIdentity();
        }

        if (!containerExists(containerName)) {
            System.out.println("Container " + containerName + " does not exist.");
            return null;
        }
        if (FabricCommand.isPartOfEnsemble(fabricService, containerName)) {
            System.out.println("Container is part of the ensemble. It can't be disconnected from fabric.");
            return null;
        }

        Container c = fabricService.getContainer(containerName);
        boolean alive = c.isAlive();
        if (!alive) {
            if (!force) {
                System.out.println("Container is not running. If you want to force disconnect it, use --force option.");
                return null;
            } else {
                System.out.println("Container is not running. It'll be disconnected anyway without cleaning its configuration, but should never be started again to not break current ensemble.");
            }
        }

        if (c.getMetadata() != null) {
            System.out.println("Container was created using Fabric. Please use fabric:container-delete command instead.");
            return null;
        }

        // are there any child containers of this container?
        List<String> dependent = new LinkedList<>();
        for (Container container : fabricService.getContainers()) {
            while (container.getParent() != null) {
                if (containerName.equals(container.getParent().getId())) {
                    dependent.add(container.getId());
                }
                container = container.getParent();
            }
        }
        if (dependent.size() > 0) {
            System.out.printf("Container %s has dependent containers (%s). Can't disconnect it. Please remove its child containers first.\n",
                    containerName, dependent);
            return null;
        }

        String response = ShellUtils.readLine(session, "Container " + containerName + " will be disconnected from Fabric. This operation is not reversible.\n" +
                "Do you want to proceed? (yes/no): ", false);
        if (!"yes".equalsIgnoreCase(response)) {
            return null;
        }

        String path = ZkPath.COMMANDS_REQUESTS_QUEUE.getPath(containerName);
        JMXRequest containerRequest = new JMXRequest().withObjectName("io.fabric8:type=Fabric").withMethod("leave");
        String command = map(containerRequest);
        curator.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL).forPath(path, PublicStringSerializer.serialize(command));

        System.out.printf("Container %s will leave Fabric and restart - cleanup will be done asynchronously.\n", containerName);

        return null;
    }

    private boolean containerExists(String containerName) {
        Container[] containers = fabricService.getContainers();
        for (Container c : containers) {
            if (containerName.equals(c.getId())) {
                return true;
            }
        }
        return false;
    }

}
