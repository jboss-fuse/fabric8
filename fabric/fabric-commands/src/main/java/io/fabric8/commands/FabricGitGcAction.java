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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.fabric8.api.FabricService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.commands.GitGcResult;
import io.fabric8.api.commands.JMXRequest;
import io.fabric8.api.commands.JMXResult;
import io.fabric8.commands.support.JMXCommandContainerActionSupport;
import io.fabric8.zookeeper.ZkPath;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.PublicStringSerializer;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.zookeeper.CreateMode;

@Command(name = FabricGitGc.FUNCTION_VALUE, scope = FabricGitGc.SCOPE_VALUE, description = FabricGitGc.DESCRIPTION)
public class FabricGitGcAction extends JMXCommandContainerActionSupport {

    @Option(name = "--aggressive", description = "Set \"--aggressive\" option for \"git gc\"", required = false, multiValued = false)
    protected boolean aggressive = false;

    // to track responses for containers
    private Map<String, JMXRequest> requests = new TreeMap<>();
    private Map<String, JMXResult> results = new TreeMap<>();

    public FabricGitGcAction(FabricService fabricService, CuratorFramework curator, RuntimeProperties runtimeProperties) {
        super(fabricService, curator, runtimeProperties);
        timeout = 10000L;
    }

    @Override
    protected void performContainerAction(String queuePath, String containerName) throws Exception {
        JMXRequest containerRequest = new JMXRequest()
                .withObjectName("io.fabric8:type=Fabric")
                .withMethod("gitGc")
                .withParam(Boolean.class, aggressive);
        requests.put(containerName, containerRequest);
        String command = map(containerRequest);
        curator.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL).forPath(queuePath, PublicStringSerializer.serialize(command));
    }

    @Override
    protected void afterEachContainer(Collection<String> names) {
        System.out.printf("Scheduled git-gc command to %d containers. Awaiting response(s).\n", names.size());

        final CountDownLatch latch = new CountDownLatch(requests.size());
        Thread waiter = null;
        try {
            waiter = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        String currentContainer = null;
                        try {
                            // requests for containers
                            for (Map.Entry<String, JMXRequest> req : requests.entrySet()) {
                                currentContainer = req.getKey();
                                List<JMXResult> containerResults = fetchResponses(currentContainer);
                                for (JMXResult result : containerResults) {
                                    if (result.getCorrelationId().equals(req.getValue().getId())) {
                                        results.put(currentContainer, result);
                                        latch.countDown();
                                        break;
                                    }
                                }
                            }

                            if (results.size() == requests.size()) {
                                break;
                            } else {
                                // active waiting - so no need for ZK watchers, etc...
                                Thread.sleep(1000);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception e) {
                            System.err.println("Problem occurred while fetching response from " + currentContainer + " container: " + e.getMessage());
                            // active waiting - so no need for ZK watchers, etc...
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e1) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            });
            waiter.start();
            boolean finished = latch.await(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                waiter.interrupt();
                System.out.println("Timeout waiting for git-gc response");
                return;
            }

            System.out.println();
            for (String containerName : results.keySet()) {
                GitGcResult response = (GitGcResult) results.get(containerName).getResponse();
                System.out.println("=== \"git gc\" result for container " + containerName + " ===");
                System.out.println("    Time elapsed: " + response.getTime() + "ms");
                if (!"".equals(response.getError())) {
                    System.out.println("    Error: " + response.getError());
                } else {
                    System.out.println("    Result: OK");
                }
                System.out.println();
            }
            System.out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            waiter.interrupt();
            System.out.println("Interrupted waiting for git-gc response");
        }
    }

    /**
     * Read responses for given containers
     * @param containerName
     * @return
     * @throws Exception
     */
    private List<JMXResult> fetchResponses(String containerName) throws Exception {
        String path = ZkPath.COMMANDS_RESPONSES.getPath(containerName);
        List<String> responses = curator.getChildren().forPath(path);
        return asResults(path, responses, GitGcResult.class);
    }

}
