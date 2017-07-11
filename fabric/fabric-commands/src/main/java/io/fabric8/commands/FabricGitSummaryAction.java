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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.fabric8.api.FabricService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.commands.GitVersion;
import io.fabric8.api.commands.GitVersions;
import io.fabric8.api.commands.JMXRequest;
import io.fabric8.api.commands.JMXResult;
import io.fabric8.commands.support.JMXCommandActionSupport;
import io.fabric8.utils.TablePrinter;
import io.fabric8.zookeeper.ZkPath;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.PublicStringSerializer;
import org.apache.felix.gogo.commands.Command;
import org.apache.zookeeper.CreateMode;

@Command(name = FabricGitSummary.FUNCTION_VALUE, scope = FabricGitSummary.SCOPE_VALUE, description = FabricGitSummary.DESCRIPTION)
public class FabricGitSummaryAction extends JMXCommandActionSupport {

    public FabricGitSummaryAction(FabricService fabricService, CuratorFramework curator, RuntimeProperties runtimeProperties) {
        super(fabricService, curator, runtimeProperties);
    }

    // to track response for master git server
    private String master;
    private JMXRequest masterRequest;
    private JMXResult masterResult;

    // to track responses for containers
    private Map<String, JMXRequest> requests = new TreeMap<>();
    private Map<String, JMXResult> results = new TreeMap<>();

    @Override
    protected void beforeEachContainer(Collection<String> names) throws Exception {
        // first, we need summary from fabric-git-server (to have something to compare local git repositories to)
        master = fabricService.getGitMaster();
        if (master == null) {
            System.out.println("Can't find container which is current git master");
            return;
        }

        System.out.println("Git master is: " + master);

        // ask master about the status first
        String queuePath = ZkPath.COMMANDS_REQUESTS_QUEUE.getPath(master);
        masterRequest = new JMXRequest().withObjectName("io.fabric8:type=GitServer").withMethod("gitVersions");
        String command = map(masterRequest);
        curator.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL).forPath(queuePath, PublicStringSerializer.serialize(command));
    }

    @Override
    protected void performContainerAction(String queuePath, String containerName) throws Exception {
        JMXRequest containerRequest = new JMXRequest().withObjectName("io.fabric8:type=Fabric").withMethod("gitVersions");
        requests.put(containerName, containerRequest);
        String command = map(containerRequest);
        curator.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL).forPath(queuePath, PublicStringSerializer.serialize(command));
    }

    @Override
    protected void afterEachContainer(Collection<String> names) {
        System.out.printf("Scheduled git-summary command to %d containers. Awaiting response(s).\n", names.size());

        final CountDownLatch latch = new CountDownLatch(requests.size() + (masterRequest == null ? 0 : 1));
        Thread waiter = null;
        try {
            waiter = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (true) {
                        String currentContainer = null;
                        try {
                            // master request
                            if (masterRequest != null && masterResult == null) {
                                currentContainer = master;
                                List<JMXResult> results = fetchResponses(master);
                                for (JMXResult result : results) {
                                    if (result.getCorrelationId().equals(masterRequest.getId())) {
                                        masterResult = result;
                                        latch.countDown();
                                        break;
                                    }
                                }
                            }

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

                            if ((masterRequest == null || masterResult != null) && results.size() == requests.size()) {
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
                            }
                        }
                    }
                }
            });
            waiter.start();
            boolean finished = latch.await(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                waiter.interrupt();
                System.out.println("Timeout waiting for git-summary response");
                return;
            }

            // before printing summary, let's check if there are out of sync containers
            Map<String, String> centralGitRepo = new HashMap<>();
            Set<String> outOfSyncContainers = new TreeSet<>();
            if (masterResult != null) {
                for (GitVersion version : ((GitVersions) masterResult.getResponse()).getVersions()) {
                    centralGitRepo.put(version.getVersion(), version.getSha1());
                }
                for (String containerName : results.keySet()) {
                    List<GitVersion> localRepo = ((GitVersions) results.get(containerName).getResponse()).getVersions();
                    for (GitVersion version : localRepo) {
                        String ref = centralGitRepo.get(version.getVersion());
                        if (ref == null) {
                            // local container knows about version, which is not tracked in central repo
                            outOfSyncContainers.add(containerName);
                        } else if (!ref.equals(version.getSha1())) {
                            // version not in sync
                            outOfSyncContainers.add(containerName);
                        }
                    }
                    if (localRepo.size() != centralGitRepo.size()) {
                        // extra or not-in-sync versions handled, so this must mean some central version is not
                        // available locally
                        outOfSyncContainers.add(containerName);
                    }
                }
                if (outOfSyncContainers.size() > 0) {
                    System.out.println();
                    System.out.println("Containers that require synchronization: " + outOfSyncContainers);
                    System.out.println("Please use \"fabric:git-synchronize\" command");
                }
            } else {
                System.out.println();
                System.out.println("Can't determine synchronization status of containers - no master Git repository detected");
            }

            // now summary time
            if (masterResult != null) {
                System.out.println();
                printVersions("=== Summary for master Git repository (container: " + master + ") ===", ((GitVersions) masterResult.getResponse()).getVersions());
            }
            System.out.println();
            for (String containerName : results.keySet()) {
                printVersions("=== Summary for local Git repository (container: " + containerName + ") ===", ((GitVersions) results.get(containerName).getResponse()).getVersions());
                System.out.println();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            waiter.interrupt();
            System.out.println("Interrupted waiting for git-summary response");
        }
    }

    private void printVersions(String title, List<GitVersion> versions) {
        System.out.println(title);
        TablePrinter table = new TablePrinter();
        table.columns("version", "SHA1", "timestamp", "message");
        for (GitVersion version : versions) {
            table.row(version.getVersion(), version.getSha1(), version.getTimestamp(), version.getMessage());
        }
        table.print();
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
        return asResults(path, responses, GitVersions.class);
    }

}
