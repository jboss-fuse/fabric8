/**
 *  Copyright 2005-2015 Red Hat, Inc.
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
package io.fabric8.service;

import java.io.File;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.fabric8.api.Container;
import io.fabric8.api.PortService;
import org.apache.commons.io.FileUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class ZookeeperPortServiceTest {

    public static Logger LOG = LoggerFactory.getLogger(ZookeeperPortServiceTest.class);

    private CuratorFramework curator1;
    private CuratorFramework curator2;
    private NIOServerCnxnFactory cnxnFactory;
    private ZookeeperPortService portService1;
    private ZookeeperPortService portService2;

    private Container c1;
    private Container c2;

    @Before
    public void init() throws Exception {
        int port = findFreePort();
        cnxnFactory = startZooKeeper(port);

        curator1 = CuratorFrameworkFactory.builder()
                .connectString("localhost:" + port)
                .retryPolicy(new RetryOneTime(1000))
                .build();
        curator1.start();

        curator1.getZookeeperClient().blockUntilConnectedOrTimedOut();
        curator1.create().creatingParentsIfNeeded().forPath("/fabric/registry/ports/ip/localhost", new byte[0]);

        curator2 = CuratorFrameworkFactory.builder()
                .connectString("localhost:" + port)
                .retryPolicy(new RetryOneTime(1000))
                .build();
        curator2.start();

        curator2.getZookeeperClient().blockUntilConnectedOrTimedOut();

        portService1 = new ZookeeperPortService();
        portService1.bindCurator(curator1);
        portService1.activate();

        portService2 = new ZookeeperPortService();
        portService2.bindCurator(curator2);
        portService2.activate();

        c1 = Mockito.mock(Container.class);
        c2 = Mockito.mock(Container.class);
        Mockito.when(c1.getIp()).thenReturn("localhost");
        Mockito.when(c1.getId()).thenReturn("c1");
        Mockito.when(c2.getIp()).thenReturn("localhost");
        Mockito.when(c2.getId()).thenReturn("c2");
    }

    @After
    public void cleanup() throws Exception {
        curator1.close();
        cnxnFactory.shutdown();
    }

    @Test
    public void noPortsInitially() throws Exception {
        assertThat(portService1.findUsedPortByHost(c1).size(), equalTo(0));
    }

    @Test
    public void registerUnregisterSinglePort() throws Exception {
        assertThat(portService1.findUsedPortByHost(c1).size(), equalTo(0));
        portService1.registerPort(c1, "org.apache.karaf.management", "rmiRegistryPort", 1099);
        assertThat(portService1.findUsedPortByHost(c1).size(), equalTo(1));
        byte[] bytes = curator1.getData().forPath("/fabric/registry/ports/containers/c1/org.apache.karaf.management/rmiRegistryPort");
        assertThat(new String(bytes), equalTo("1099"));
        portService1.unregisterPort(c1, "org.apache.karaf.management", "rmiRegistryPort");
        assertThat(portService1.findUsedPortByHost(c1).size(), equalTo(0));
        bytes = curator1.getData().forPath("/fabric/registry/ports/ip/localhost");
        assertThat(new String(bytes), equalTo(""));
    }

    @Test
    public void findAndRegisterSinglePortUsingLocks() throws Exception {
        PortService.Lock lock = null;
        try {
            lock = portService1.acquirePortLock();
            Set<Integer> ports = portService1.findUsedPortByHost(c1, lock);
            assertThat(ports.size(), equalTo(0));
            portService1.registerPort(c1, "org.apache.karaf.management", "rmiRegistryPort", 1099, lock);
            ports = portService1.findUsedPortByHost(c1, lock);
            assertThat(ports.size(), equalTo(1));
        } finally {
            portService1.releasePortLock(lock);
        }
    }

    @Test
    public void multiVmPortOperations() throws Exception {
        LOG.info("Starting multithreaded ZookeeperPortService test");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        final int ITERATIONS = 100;
        Future<?> f1 = pool.submit(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < ITERATIONS; i += 2) {
                    try {
                        atomicPortAllocation(c1, portService1, i); // 0, 2, 4, ...
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }
            }
        });
        Future<?> f2 = pool.submit(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < ITERATIONS; i += 2) {
                    try {
                        atomicPortAllocation(c2, portService2, i + 1); // 1, 3, 5, ...
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }
            }
        });
        f1.get(120, TimeUnit.SECONDS);
        f2.get(120, TimeUnit.SECONDS);
        Set<Integer> ports = portService1.findUsedPortByHost(c1); // same as for c2
        assertThat(ports.size(), equalTo(ITERATIONS));
        for (int i = 0; i < ITERATIONS; i++) {
            assertThat(ports.remove(i + 1000), equalTo(true));
        }
        assertThat(ports.size(), equalTo(0));
    }

    private void atomicPortAllocation(Container container, PortService portService, int portNumber) throws Exception {
        PortService.Lock lock = null;
        try {
            lock = portService.acquirePortLock();
            Set<Integer> ports = portService.findUsedPortByHost(container, lock);
            System.out.println("ports size: " + ports.size());
            assertThat(ports.contains(1000 + portNumber), is(false));
            portService.registerPort(container, "org.apache.karaf.management", String.format("port-%03d", portNumber), 1000 + portNumber, lock);
            ports = portService.findUsedPortByHost(container, lock);
            assertThat(ports.contains(1000 + portNumber), is(true));
        } finally {
            portService.releasePortLock(lock);
        }
    }

    private int findFreePort() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();
        return port;
    }

    private NIOServerCnxnFactory startZooKeeper(int port) throws Exception {
        FileUtils.deleteDirectory(new File("target/zk-ports/data"));
        ServerConfig cfg = new ServerConfig();
        cfg.parse(new String[]{Integer.toString(port), "target/zk-ports/data"});

        ZooKeeperServer zkServer = new ZooKeeperServer();
        FileTxnSnapLog ftxn = new FileTxnSnapLog(new File(cfg.getDataLogDir()), new File(cfg.getDataDir()));
        zkServer.setTxnLogFactory(ftxn);
        zkServer.setTickTime(cfg.getTickTime());
        zkServer.setMinSessionTimeout(cfg.getMinSessionTimeout());
        zkServer.setMaxSessionTimeout(cfg.getMaxSessionTimeout());
        NIOServerCnxnFactory cnxnFactory = new NIOServerCnxnFactory();
        cnxnFactory.configure(cfg.getClientPortAddress(), cfg.getMaxClientCnxns());
        cnxnFactory.startup(zkServer);
        return cnxnFactory;
    }

}
