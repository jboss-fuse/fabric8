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

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.api.FabricService;
import io.fabric8.utils.TablePrinter;
import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.karaf.shell.console.AbstractAction;
import org.apache.zookeeper.data.Stat;

import static io.fabric8.zookeeper.utils.ZooKeeperUtils.exists;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getAllChildren;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getSubstitutedData;

@Command(name = ClusterList.FUNCTION_VALUE, scope = ClusterList.SCOPE_VALUE, description = ClusterList.DESCRIPTION)
public class ClusterListAction extends AbstractAction {

    protected static String CLUSTER_PREFIX = "/fabric/registry/clusters";

    @Option(name = "-v", aliases = {"--verbose"}, description = "Verbose output", required = false, multiValued = false)
    boolean verbose = false;

    @Argument(required = false, description = "Path of the fabric registry node (Zookeeper registry node) to list. Relative paths are evaluated relative to the base node, /fabric/registry/clusters. If not specified, all clusters are listed.")
    String path = "";

    private final FabricService fabricService;
    private final CuratorFramework curator;

    ClusterListAction(FabricService fabricService, CuratorFramework curator) {
        this.fabricService = fabricService;
        this.curator = curator;
    }

    public CuratorFramework getCurator() {
        return curator;
    }

    @Override
    protected Object doExecute() throws Exception {
        String realPath = path;
        if (!realPath.startsWith("/")) {
            realPath = CLUSTER_PREFIX;
            if (path.length() > 0) {
                realPath += "/" + path;
            }
        }

        if (verbose) {
            printClusterV(realPath, System.out);
        } else {
            printCluster(realPath, System.out);
        }

        return null;
    }

    /**
     * Old {@code printCluster()}.
     * @param dir
     * @param out
     * @throws Exception
     */
    protected void printCluster(String dir, PrintStream out) throws Exception {
        // do we have any clusters at all?
        if (exists(getCurator(), dir) == null) {
            return;
        }
        List<String> children = getAllChildren(getCurator(), dir);
        Map<String, Map<String,ClusterNode>> clusters = new TreeMap<String, Map<String,ClusterNode>>();
        for (String child : children) {
            byte[] data = getCurator().getData().forPath(child);
            if (data != null && data.length > 0) {
                String text = new String(data).trim();
                if (!text.isEmpty()) {
                    String clusterName = getClusterName(dir, child);
                    Map<String, ClusterNode> cluster = clusters.get(clusterName);
                    if (cluster == null) {
                        cluster = new TreeMap<String, ClusterNode>();
                        clusters.put(clusterName, cluster);
                    }

                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> map = null;
                    try {
                        map = mapper.readValue(data, HashMap.class);
                    } catch (JsonParseException e){
                        log.error("Error parsing JSON string: {}", text);
                        throw e;
                    }

                    ClusterNode node = null;

                    Object id = value(map, "id", "container");
                    if (id != null) {
                        Object oagent = value(map, "container", "agent");
                        String agent = oagent == null ? "" : oagent.toString();
                        List services = (List) value(map, "services");

                        node = cluster.get(id.toString());
                        if (node == null) {
                            node = new ClusterNode();
                            cluster.put(id.toString(), node);
                        }

                        if (services != null) {
                            if (!services.isEmpty()) {
                                for (Object service : services) {
                                    node.services.add(getSubstitutedData(getCurator(), service.toString()));
                                }

                                node.masters.add(agent);
                            } else {
                                node.slaves.add(agent);
                            }
                        } else {
                            Object started = value(map, "started");
                            if( started == Boolean.TRUE ) {
                                node.masters.add(agent);
                            } else {
                                node.slaves.add(agent);
                            }
                        }
                    }
                }
            }
        }

        TablePrinter table = new TablePrinter();
        table.columns("cluster", "masters", "slaves", "services");

        for (String clusterName : clusters.keySet()) {
            Map<String, ClusterNode> nodes = clusters.get(clusterName);
            table.row(clusterName, "", "", "", "");
            for (String nodeName : nodes.keySet()) {
                ClusterNode node = nodes.get(nodeName);
                table.row("   " + nodeName,
                        printList(node.masters),
                        printList(node.slaves),
                        printList(node.services));
            }
        }
        table.print();
    }

    /**
     * <p>New method for printing cluster data - to not break possible parsers of previous format</p>
     * @param dir
     * @param out
     */
    protected void printClusterV(String dir, PrintStream out) throws Exception {
        Stat stat = exists(getCurator(), dir);
        if (stat == null) {
            out.println("No cluster information for path " + dir);
            return;
        }
        List<String> children = getAllChildren(getCurator(), dir);
        Map<String, Map<String, ClusterNode>> clusters = new TreeMap<String, Map<String, ClusterNode>>();

        for (String child : children) {
            byte[] data = getCurator().getData().forPath(child);
            if (data != null && data.length > 0) {
                String text = new String(data).trim();
                if (!text.isEmpty()) {
                    String clusterName = getClusterName(dir, child);
                    Map<String, ClusterNode> cluster = clusters.get(clusterName);
                    if (cluster == null) {
                        cluster = new TreeMap<String, ClusterNode>();
                        clusters.put(clusterName, cluster);
                    }

                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> map = null;
                    try {
                        map = mapper.readValue(data, HashMap.class);
                    } catch (JsonParseException e) {
                        log.error("Error parsing JSON string: {}", text);
                        throw e;
                    }

                    ClusterNode node = null;

                    /*
                     * We have these nodes to register clusters / cluster members
                     * - /fabric/registry/clusters/amq
                     * - /fabric/registry/clusters/amq/{group}
                     * - /fabric/registry/clusters/apis/rest/{apiName}
                     * - /fabric/registry/clusters/apis/rest/{name}/{version}/{container}
                     * - /fabric/registry/clusters/apis/ws/{apiName}
                     * - /fabric/registry/clusters/apis/ws/{name}/{version}/{container}
                     * - /fabric/registry/clusters/git
                     * - /fabric/registry/clusters/servlets/{group}
                     * - /fabric/registry/clusters/webapps
                     * - /fabric/registry/clusters/webapps/{group}
                     * - /fabric/registry/clusters/webapps/{name}/{version}/{container}
                     *
                     * Only /fabric/registry/clusters/git children store json with same ID ("fabric-repo")
                     */

                    Object id = value(map, "id", "container");
                    if (id != null) {
                        Object oagent = value(map, "container", "agent");
                        String agent = oagent == null ? null : oagent.toString();
                        List<?> services = (List<?>) value(map, "services");

                        node = cluster.get(id.toString());
                        if (node == null) {
                            node = new ClusterNode();
                            cluster.put(id.toString(), node);
                        }

                        if (services != null) {
                            if (!services.isEmpty()) {
                                boolean first = true;
                                for (Object service : services) {
                                    node.combined.add(new String[] { agent, null, getSubstitutedData(getCurator(), service.toString()) });
                                }
                            } else {
                                node.combined.add(new String[] { null, agent, null });
                            }
                        } else {
                            Object started = value(map, "started");
                            if (started == Boolean.TRUE) {
                                node.combined.add(new String[] { agent, null, null });
                            } else {
                                node.combined.add(new String[] { null, agent, null });
                            }
                        }
                    }
                }
            }
        }


        TablePrinter table = new TablePrinter();
        table.columns("cluster", "masters", "slaves", "services");

        for (String clusterName : clusters.keySet()) {
            Map<String, ClusterNode> nodes = clusters.get(clusterName);
            table.row(clusterName, "", "", "");
            for (String nodeName : nodes.keySet()) {
                ClusterNode node = nodes.get(nodeName);
                // sort the combined information
                sortClusterInformation(node.combined);
                boolean first = true;
                String previousMaster = null;
                String previousSlave = null;
                for (String[] row : node.combined) {
                    String master = row[0] == null ? "-" : row[0];
                    String slave = row[1] == null ? "-" : row[1];
                    String service = row[2] == null ? "-" : row[2];

                    if (!"-".equals(master) && master.equals(previousMaster)) {
                        master = "";
                        slave = "";
                    } else {
                        previousMaster = master;
                    }
                    if (!"-".equals(slave) && slave.equals(previousSlave)) {
                        master = "";
                        slave = "";
                    } else {
                        previousSlave = slave;
                    }
                    if (first) {
                        table.row("   " + nodeName, master, slave, service);
                        first = false;
                    } else {
                        table.row("", master, slave, service);
                    }
                }
            }
        }
        table.print();
    }

    /**
     * Each row contains master, slave and service(s). In theory, nothing prevents a JSON for a container
     * to contain multiple services
     * @param combined
     */
    private void sortClusterInformation(List<String[]> combined) {
        Collections.sort(combined, new Comparator<String[]>() {
            @Override
            public int compare(String[] o1, String[] o2) {
                for (int i = 0; i < 3; i++) {
                    if (o1[i] == null && o2[i] == null) {
                        continue;
                    }
                    if (o1[i] != null) {
                        if (o1[i].equals(o2[i])) {
                            continue;
                        }
                        if (o2[i] == null) {
                            return -1;
                        }
                        return o1[i].compareTo(o2[i]);
                    } else /* o2[i] != null */{
                        return 1;
                    }

                }
                return 0;
            }
        });
    }

    protected String printList(List list) {
        if (list.isEmpty()) {
            return "-";
        }
        String text = list.toString();
        return text.substring(1, text.length() - 1);
    }

    protected Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    protected String getClusterName(String rootDir, String dir) {
        String clusterName = dir;
        clusterName = clusterName.substring(0, clusterName.lastIndexOf("/"));
        if (clusterName.startsWith(rootDir)) {
            clusterName = clusterName.substring(rootDir.length());
        }
        if (clusterName.startsWith("/")) {
            clusterName = clusterName.substring(1);
        }
        if (clusterName.length() == 0) {
            clusterName = ".";
        }
        return clusterName;
    }

    protected static class ClusterNode {
        public List<String> masters = new ArrayList<>();
        public List<String> services = new ArrayList<>();
        public List<String> slaves = new ArrayList<>();

        public List<String[]> combined = new ArrayList<>();

        @Override
        public String toString() {
            return masters + " " + services + " " + slaves;
        }
    }

}
