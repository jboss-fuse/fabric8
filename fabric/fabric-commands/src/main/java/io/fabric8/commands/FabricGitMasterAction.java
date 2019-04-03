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

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.api.Container;
import io.fabric8.api.FabricService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.commands.JMXRequest;
import io.fabric8.api.commands.JMXResult;
import io.fabric8.commands.support.JMXCommandActionSupport;
import io.fabric8.zookeeper.ZkPath;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.utils.ZKPaths;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.zookeeper.KeeperException;

@Command(name = FabricGitMaster.FUNCTION_VALUE, scope = FabricGitMaster.SCOPE_VALUE, description = FabricGitMaster.DESCRIPTION)
public class FabricGitMasterAction extends JMXCommandActionSupport {

    @Argument(index = 0, name = "container", description = "New Git master container", required = false, multiValued = false)
    protected String container = null;

    public FabricGitMasterAction(FabricService fabricService, CuratorFramework curator, RuntimeProperties runtimeProperties) {
        super(fabricService, curator, runtimeProperties);
    }

    // to track responses for containers
    private Map<String, JMXRequest> requests = new TreeMap<>();
    private Map<String, JMXResult> results = new TreeMap<>();

    @Override
    protected Object doExecute() throws Exception {
        if (container == null || "".equals(container.trim())) {
            // get Git master
            String master = fabricService.getGitMaster();
            if (master == null) {
                System.out.println("Can't find container which is current Git master");
                return null;
            }

            System.out.println("Current Git master is: " + master);
            return null;
        }

        if (!containerExists(container)) {
            System.out.println("Container " + container + " does not exist.");
            return null;
        }

        String master = fabricService.getGitMaster();
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        try {
            // set Git master. It's done by force removal all ZK paths related to git cluster and created by:
            // - current git master
            // - all next pretenders up to, excluding the container we want to be next master
            // of course if chosen container is not among the pretenders, error will be printed.
            List<String> children = curator.getChildren().forPath(ZkPath.GIT.getPath());
            // compare in reverse order, so when removing them one by one, none of these nodes will
            // attempt to become git master!
            Collections.sort(children, new Comparator<String>() {
                @Override
                public int compare(String left, String right) {
                    return right.compareTo(left);
                }
            });
            List<String> toRemove = new LinkedList<>();
            JsonNode newMaster = null;
            for (String path : children) {
                String fullPath = ZKPaths.makePath(ZkPath.GIT.getPath(), path);
                byte[] data = curator.getData().forPath(fullPath);
                JsonNode tree = mapper.readTree(data);
                if (tree != null && tree.get("container") != null) {
                    String c = tree.get("container").asText();
                    if (!c.equals(container)) {
                        toRemove.add(fullPath);
                    } else {
                        // we have new master
                        newMaster = tree;
                        if (c.equals(master)) {
                            System.out.println("Container " + container + " is already a Git master.");
                            return null;
                        }
                    }
                }
            }

            if (newMaster == null) {
                System.out.println("Container \"" + container + "\" didn't register Git cluster member.");
                return null;
            }

            System.out.println("Changing Git master to new cluster member: " + newMaster.get("container").asText());
            if (toRemove.size() > 0) {
                for (String fullPath : toRemove) {
                    try {
                        curator.delete().forPath(fullPath);
                    } catch (KeeperException.NoNodeException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Problem occurred when choosing new Git master: " + e.getMessage());
            log.error(e.getMessage(), e);
        } finally {
            mapper.getTypeFactory().clearCache();
        }

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
