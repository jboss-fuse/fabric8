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

import io.fabric8.api.FabricService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.commands.JMXRequest;
import io.fabric8.commands.support.JMXCommandActionSupport;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.PublicStringSerializer;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.zookeeper.CreateMode;

@Command(name = FabricGitSynchronize.FUNCTION_VALUE, scope = FabricGitSynchronize.SCOPE_VALUE, description = FabricGitSynchronize.DESCRIPTION)
public class FabricGitSynchronizeAction extends JMXCommandActionSupport {

    @Option(name = "-p", aliases = { "--allow-push" }, description = "Whether containers are allowed to push local Git repository state to central Git repository", required = false, multiValued = false)
    protected boolean allowPush = false;

    public FabricGitSynchronizeAction(FabricService fabricService, CuratorFramework curator, RuntimeProperties runtimeProperties) {
        super(fabricService, curator, runtimeProperties);
    }

    @Override
    protected void performContainerAction(String queuePath, String containerName) throws Exception {
        String command = map(new JMXRequest()
                .withObjectName("io.fabric8:type=Fabric").withMethod("gitSynchronize")
                .withParam(Boolean.class, allowPush));
        curator.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL).forPath(queuePath, PublicStringSerializer.serialize(command));
    }

    @Override
    protected void afterEachContainer(Collection<String> names) {
        System.out.printf("Scheduled git-synchronize command to %d containers\n\n", names.size());
    }

}
