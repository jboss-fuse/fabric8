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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.api.Container;
import io.fabric8.api.CreateChildContainerOptions;
import io.fabric8.api.CreateContainerMetadata;
import io.fabric8.api.FabricException;
import io.fabric8.api.FabricService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.commands.GitVersions;
import io.fabric8.api.commands.JMXRequest;
import io.fabric8.api.commands.JMXResult;
import io.fabric8.api.commands.LocationInformation;
import io.fabric8.commands.support.JMXCommandActionSupport;
import io.fabric8.service.child.ChildContainerProvider;
import io.fabric8.utils.shell.ShellUtils;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.PublicStringSerializer;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

@Command(name = Instances.FUNCTION_VALUE, scope = Instances.SCOPE_VALUE, description = Instances.DESCRIPTION)
final class InstancesAction extends JMXCommandActionSupport {

    private static DateFormat DF = new SimpleDateFormat("yyyyMMddHHmmss");

    @Option(name = "-W", multiValued = false, required = false, description = "Recreate FUSE_HOME/instances/instance.properties")
    protected boolean originalFile = false;

    @Option(name = "--force", multiValued = false, required = false, description = "Do not ask for confirmation")
    protected boolean force = false;

    @Option(name = "--container", multiValued = false, required = false, description = "Recreate instance.properties for specific container")
    protected String container;

    @Argument(required = false, index = 0, multiValued = false, description = "Target filename to write generated instance.properties")
    private String fileName;

    InstancesAction(RuntimeProperties runtimeProperties, FabricService fabricService, CuratorFramework curatorFramework) {
        super(fabricService, curatorFramework, runtimeProperties);
    }

    @Override
    protected Object doExecute() throws Exception {
        ContainerData rootData = null;
        Map<String, ContainerData> containerData = new LinkedHashMap<>();

        String toCheck = container;
        if (toCheck == null || "".equals(toCheck.trim())) {
            toCheck = runtimeProperties.getRuntimeIdentity();
        }
        Container checked = null;
        try {
            checked = fabricService.getContainer(toCheck);
        } catch (FabricException e) {
            System.err.println("Can't find container named \"" + toCheck + "\"");
            return null;
        }

        String home = null;
        String instances = null;

        if (checked.isRoot()) {
            // asking about root container (normal root or ssh)
            if (runtimeProperties.getRuntimeIdentity().equals(checked.getId())) {
                // command invoked on a container, which is root and instance.properties is for this container - easy!
                home = runtimeProperties.getProperty("karaf.home");
                instances = runtimeProperties.getProperty("karaf.instances");
            } else {
                // maybe it's our parent?
                try {
                    Container current = fabricService.getCurrentContainer();
                    while (!current.isRoot()) {
                        current = current.getParent();
                    }
                    if (current.getId().equals(checked.getId())) {
                        // it is!
                        home = runtimeProperties.getProperty("karaf.home");
                        instances = runtimeProperties.getProperty("karaf.instances");
                    }
                } catch (FabricException ignore) {
                }
            }
        } else {
            // asking about child container
            if (runtimeProperties.getRuntimeIdentity().equals(checked.getId())) {
                // command invoked on child container and asking about this child container
                home = runtimeProperties.getProperty("karaf.home");
                instances = runtimeProperties.getProperty("karaf.instances");
            } else {
                // maybe it's our child?
                Container _c = checked;
                while (!_c.isRoot()) {
                    _c = _c.getParent();
                }
                if (runtimeProperties.getRuntimeIdentity().equals(_c.getId())) {
                    // it is!
                    home = runtimeProperties.getProperty("karaf.home");
                    instances = runtimeProperties.getProperty("karaf.instances");
                }
            }
        }

        if (checked.isRoot()) {
            if (container == null) {
                System.out.println("Generating instance.properties for current container");
            } else {
                System.out.println("Generating instance.properties for container \"" + container + "\"");
            }
        } else {
            while (!checked.isRoot()) {
                checked = checked.getParent();
            }
            if (container == null) {
                System.out.println("Generating instance.properties for current container's root container \"" + checked.getId() + "\"");
            } else {
                System.out.println("Generating instance.properties for \"" + container + "\" container's root container \"" + checked.getId() + "\"");
            }
        }

        File instancesDir = null;
        if (home != null && instances != null) {
            // because we have home and instances, we could overwrite instance.properties
            // otherwise we can only print it
            instancesDir = new File(instances);
        } else {
            System.out.println("Getting location information for container \"" + checked.getId() + "\"");
            Thread waiter = null;
            try {
                // we have to do it the hard way. SSH connection is hard, so let's use ZK commands
                final String containerName = checked.getId();
                String path = ZkPath.COMMANDS_REQUESTS_QUEUE.getPath(containerName);
                final JMXRequest containerRequest = new JMXRequest().withObjectName("io.fabric8:type=Fabric").withMethod("karafLocations");
                final JMXResult[] containerResult = new JMXResult[1];
                String command = map(containerRequest);
                curator.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL).forPath(path, PublicStringSerializer.serialize(command));

                final CountDownLatch latch = new CountDownLatch(1);
                waiter = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (true) {
                            try {
                                if (containerResult[0] == null) {
                                    String path = ZkPath.COMMANDS_RESPONSES.getPath(containerName);
                                    List<String> responses = curator.getChildren().forPath(path);
                                    containerResult[0] = asResult(containerRequest.getId(), path, responses, GitVersions.class);
                                }

                                if (containerResult[0] != null) {
                                    latch.countDown();
                                    break;
                                } else {
                                    // active waiting - so no need for ZK watchers, etc...
                                    Thread.sleep(1000);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            } catch (Exception e) {
                                System.err.println("Problem occurred while fetching response from " + containerName + " container: " + e.getMessage());
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
                boolean finished = latch.await(5000, TimeUnit.MILLISECONDS);
                if (!finished) {
                    waiter.interrupt();
                    System.out.println("Timeout waiting for location information about remote container \"" + containerName + "\". Please run this command on the container, where instance.properties are to be generated.");
                    return null;
                }

                if (containerResult[0] != null) {
                    home = ((LocationInformation) containerResult[0].getResponse()).getHome();
                    instances = ((LocationInformation) containerResult[0].getResponse()).getInstances();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (waiter != null) {
                    waiter.interrupt();
                }
                System.err.println(e.getMessage());
                System.err.println("Can't get location information about remote container \"" + checked.getId() + "\". Please run this command on the container, where instance.properties are to be generated.");
                return null;
            } catch (Exception e) {
                System.err.println(e.getMessage());
                System.err.println("Can't get location information about remote container \"" + checked.getId() + "\". Please run this command on the container, where instance.properties are to be generated.");
                return null;
            }
        }

        // clean up commands
        try {
            String path = ZkPath.COMMANDS_RESPONSES.getPath(checked.getId());
            List<String> responses = curator.getChildren().forPath(path);
            for (String r : responses) {
                curator.delete().forPath(path + "/" + r);
            }
        } catch (Exception ignored) {
        }

        if (home == null) {
            System.err.println("Can't get location information about remote container \"" + checked.getId() + "\". Please run this command on the container, where instance.properties are to be generated.");
            return null;
        }

//        System.out.println("home: " + home);
//        System.out.println("base: " + base);
//        System.out.println("instances: " + instances);

        Container[] containers = fabricService.getContainers();

        for (Container container : fabricService.getContainers()) {
            // skip containers which are not current root or its (real) child containers
            Container _c = container;
            while (!_c.isRoot()) {
                _c = _c.getParent();
            }
            if (!checked.getId().equals(_c.getId())) {
                continue;
            }

            ContainerData cd = new ContainerData();
            String id = container.getId();
            if (container.isRoot()) {
                rootData = cd;
            } else {
                containerData.put(id, cd);
            }

            // for root container ONLY we store additional properties and opts should be empty
            //      item.0.name = root
            //      item.0.loc = /data/servers/jboss-fuse-6.3.0.redhat-475
            //      item.0.pid = 68399
            //      item.0.root = true
            //      ssh.port = 8101
            //      rmi.registry.port = 1099
            //      rmi.server.port = 44444
            //      item.0.opts =
            // normal child container has:
            //      item.1.name = child1
            //      item.1.root = false
            //      item.1.loc = /data/servers/jboss-fuse-6.3.0.redhat-475/instances/child1
            //      item.1.pid = 125131
            //      item.1.opts = -server -Dcom.sun.management.jmxremote ...

            cd.name = container.getId();
            cd.root = container.isRoot();
            cd.pid = container.getProcessId();
            if (cd.pid == null) {
                cd.pid = 0L;
            }
            if (container.isRoot()) {
                String sshPortPath = ZkPath.PORTS_CONTAINER_PID_KEY.getPath(container.getId(), "org.apache.karaf.shell", "sshPort");
                String rmiRegistryPortPath = ZkPath.PORTS_CONTAINER_PID_KEY.getPath(container.getId(), "org.apache.karaf.management", "rmiRegistryPort");
                String rmiServerPortPath = ZkPath.PORTS_CONTAINER_PID_KEY.getPath(container.getId(), "org.apache.karaf.management", "rmiServerPort");
                String sshPort = null;
                try {
                    sshPort = ZooKeeperUtils.getStringData(curator, sshPortPath);
                } catch (KeeperException.NoNodeException ignored) {
                    sshPort = "-1";
                }
                String rmiRegistryPort = null;
                try {
                    rmiRegistryPort = ZooKeeperUtils.getStringData(curator, rmiRegistryPortPath);
                } catch (KeeperException.NoNodeException ignored) {
                    rmiRegistryPort = "-1";
                }
                String rmiServerPort = null;
                try {
                    rmiServerPort = ZooKeeperUtils.getStringData(curator, rmiServerPortPath);
                } catch (KeeperException.NoNodeException ignored) {
                    rmiServerPort = "-1";
                }

                try {
                    if (sshPort != null) {
                        cd.sshPort = Integer.parseInt(sshPort);
                    }
                } catch (NumberFormatException ignored) {
                }
                try {
                    if (sshPort != null) {
                        cd.rmiRegistryPort = Integer.parseInt(rmiRegistryPort);
                    }
                } catch (NumberFormatException ignored) {
                }
                try {
                    if (sshPort != null) {
                        cd.rmiServerPort = Integer.parseInt(rmiServerPort);
                    }
                } catch (NumberFormatException ignored) {
                }
                cd.opts = "";
                cd.loc = home;
            } else {
                cd.loc = String.format("%s/%s", instances, container.getId());
                CreateContainerMetadata<?> metadata = container.getMetadata();
                StringBuilder jvmOpts = ChildContainerProvider.buildJvmOpts((CreateChildContainerOptions) metadata.getCreateOptions(), fabricService);
                cd.opts = extractZookeeperCredentials(jvmOpts.toString(), new ArrayList<String>());
            }
        }

        StringWriter sw = new StringWriter();
        sw.write(String.format("count = %d%n", containerData.size() + (rootData == null ? 0 : 1)));
        int count = 0;
        if (rootData != null) {
            sw.write(String.format("item.0.name = %s%n", rootData.name));
            sw.write(String.format("item.0.loc = %s%n", rootData.loc));
            sw.write(String.format("item.0.pid = %d%n", rootData.pid));
            sw.write(String.format("item.0.root = true%n"));
            if (rootData.sshPort != -1) {
                sw.write(String.format("ssh.port = %d%n", rootData.sshPort));
            }
            if (rootData.rmiRegistryPort != -1) {
                sw.write(String.format("rmi.registry.port = %d%n", rootData.rmiRegistryPort));
            }
            if (rootData.rmiServerPort != -1) {
                sw.write(String.format("rmi.server.port = %d%n", rootData.rmiServerPort));
            }
            sw.write(String.format("item.0.opts = %n"));
            count++;
        }
        for (ContainerData cd : containerData.values()) {
            sw.write(String.format("item.%d.name = %s%n", count, cd.name));
            sw.write(String.format("item.%d.root = false%n", count));
            sw.write(String.format("item.%d.loc = %s%n", count, cd.loc));
            sw.write(String.format("item.%d.pid = %d%n", count, cd.pid));
            sw.write(String.format("item.%d.opts = %s%n", count, cd.opts));
            count++;
        }

        if (this.originalFile) {
            if (instancesDir == null) {
                System.out.println("Can't write instance.properties that should be stored on remote container. " +
                        "Please copy the output to proper location at \"" + checked.getId() + "\" container.");
            } else {
                File instancesFile = new File(instancesDir, "instance.properties");
                if (!force) {
                    String response = ShellUtils.readLine(session, "Overwrite " + instancesFile + "? (yes/no): ", false);
                    if ("yes".equalsIgnoreCase(response)) {
                        force = true;
                    }
                }
                if (force) {
                    if (instancesFile.exists()) {
                        instancesFile.renameTo(new File(instancesDir, "instance.properties-" + DF.format(new Date())));
                    }
                    try (BufferedWriter w = new BufferedWriter(new FileWriter(instancesFile))) {
                        w.write(sw.toString());
                    }
                }
                return null;
            }
        }

        System.out.println("Resulting instance.properties file:");
        System.out.println(sw.toString());

        return null;
    }

    private JMXResult asResult(String id, String path, List<String> responses, Class<GitVersions> gitVersionsClass) throws Exception {
        ObjectMapper mapper = getObjectMapper();
        JMXResult result = null;
        for (String responsePath : responses) {
            byte[] bytes = curator.getData().forPath(path + "/" + responsePath);
            String response = PublicStringSerializer.deserialize(bytes);
            result = mapper.readValue(response, JMXResult.class);
            if (result.getCorrelationId().equals(id)) {
                if (result.getResponse() instanceof String) {
                    try {
                        result.setResponse(mapper.readValue((String)result.getResponse(), LocationInformation.class));
                    } catch (JsonMappingException | IllegalArgumentException ignore) {
                    }
                }
            }
        }
        mapper.getTypeFactory().clearCache();
        return result;
    }

    /**
     * A copy from {@code org.apache.karaf.admin.internal.AdminServiceImpl#extractZookeeperCredentials()} for
     * the purpose of recreating container options
     * @param javaOpts
     * @param zookeeperCredentials
     * @return
     */
    static String extractZookeeperCredentials(String javaOpts, List<String> zookeeperCredentials) {
        zookeeperCredentials.clear();

        javaOpts = extract(javaOpts, "zookeeper.url", zookeeperCredentials);
        javaOpts = extract(javaOpts, "zookeeper.password.encode", zookeeperCredentials);
        javaOpts = extract(javaOpts, "zookeeper.password", zookeeperCredentials);

        return javaOpts;
    }

    /**
     * Extracts given property from property string
     * @param javaOpts
     * @param property
     * @param zookeeperCredentials
     * @return
     */
    private static String extract(String javaOpts, String property, List<String> zookeeperCredentials) {
        if (javaOpts != null && javaOpts.contains(property)) {
            StringBuilder sb = new StringBuilder();
            int id = javaOpts.indexOf("-D" + property);
            if (id > 0) {
                int from = id + ("-D" + property).length();
                // states: 0: before =, 1: waiting for value, 2: waiting for end, 3: end
                int state = 0;
                char delim = 0;
                int pbegin = id;
                int pend = -1;
                int vbegin = -1;
                int vend = -1;
                while (state < 3 && from < javaOpts.length()) {
                    char c = javaOpts.charAt(from);
                    switch (state) {
                        case 0:
                            if (c == ' ' || c == '\t') {
                                break;
                            }
                            if (c == '=') {
                                state = 1;
                            }
                            break;
                        case 1:
                            if (c == ' ' || c == '\t') {
                                break;
                            } else {
                                vbegin = from;
                                if (c == '\'' || c == '"') {
                                    delim = c;
                                    vbegin++;
                                }
                                state = 2;
                            }
                            break;
                        case 2:
                            if (delim != 0 && c == delim && javaOpts.charAt(from - 1) != '\\') {
                                pend = from + 1;
                                vend = from;
                                state = 3;
                            } else if (delim == 0 && c == ' ') {
                                pend = from;
                                vend = from;
                                state = 3;
                            }
                            break;
                    }
                    from++;
                }
                if (pend == -1) {
                    pend = javaOpts.length();
                    vend = javaOpts.length();
                }
                if (pend > pbegin) {
                    String a = javaOpts.substring(0, pbegin);
                    String b = javaOpts.substring(pend);
                    String value = javaOpts.substring(vbegin, vend);
                    zookeeperCredentials.add(value);
                    javaOpts = a.trim() + " " + b.trim();
                }
            }
        }

        return javaOpts;
    }

    private class ContainerData {

        String name;
        String opts;
        boolean root;
        public String loc;
        int sshPort = -1;
        int rmiRegistryPort = -1;
        int rmiServerPort = -1;
        public Long pid;
    }

}
