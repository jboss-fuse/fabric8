/**
 * Copyright 2005-2016 Red Hat, Inc.
 * <p/>
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.mq.fabric;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.jms.JMSException;

import io.fabric8.groups.Group;
import io.fabric8.groups.GroupListener;
import io.fabric8.groups.NodeState;
import io.fabric8.groups.internal.ZooKeeperGroup;
import io.fabric8.mq.fabric.discovery.OsgiFabricDiscoveryAgent;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.AbstractLocker;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.command.DiscoveryEvent;
import org.apache.activemq.store.PersistenceAdapter;
import org.apache.activemq.transport.discovery.DiscoveryListener;
import org.apache.activemq.util.ServiceStopper;
import org.apache.activemq.util.SocketProxy;
import org.apache.activemq.transport.TransportListener;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static org.apache.zookeeper.server.ServerCnxnFactory.createFactory;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ServiceFactoryTest {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceFactoryTest.class);

    ServerCnxnFactory standaloneServerFactory;
    CuratorFramework curator;
    ActiveMQServiceFactory underTest;
    Random random = new Random();
    ZooKeeperServer server;
    int zkPort;

    @Before
    public void infraUp() throws Exception {
        int tickTime = 500;
        int numConnections = 5000;
        File dir = new File("target", "zookeeper" + random.nextInt()).getAbsoluteFile();

        server = new ZooKeeperServer(dir, dir, tickTime);
        standaloneServerFactory = createFactory(0, numConnections);
        zkPort = standaloneServerFactory.getLocalPort();

        System.setProperty("zookeeper.url","localhost:"+zkPort);
        standaloneServerFactory.startup(server);

        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString("localhost:" + zkPort)
                .sessionTimeoutMs(5000)
                .retryPolicy(new RetryNTimes(5000, 1000));

        curator = builder.build();
        LOG.debug("Starting curator " + curator);
        curator.start();
    }

    @After
    public void infraDown() throws Exception {
        System.clearProperty("zookeeper.url");
        curator.close();
        standaloneServerFactory.shutdown();
    }

    @Test
    public void testDeletedStopsBroker() throws Exception {

        underTest = new ActiveMQServiceFactory();
        underTest.curator = curator;

        Properties props = new Properties();
        props.put("config", "amq.xml");
        props.put("broker-name", "amq");
        props.put("connectors", "openwire");

        for (int i=0; i<10; i++) {

            underTest.updated("b", props);

            ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("failover:(tcp://localhost:61616)?useExponentialBackOff=false&timeout=10000");

            final ActiveMQConnection connection = (ActiveMQConnection) cf.createConnection();
            connection.start();

            assertTrue("is connected", connection.getTransport().isConnected());

            final CountDownLatch interrupted = new CountDownLatch(1);

            connection.getTransport().setTransportListener(new TransportListener() {
                @Override
                public void onCommand(Object o) {
                }

                @Override
                public void onException(IOException e) {
                }

                @Override
                public void transportInterupted() {
                    interrupted.countDown();
                }

                @Override
                public void transportResumed() {
                }
            });

            underTest.deleted("b");

            assertTrue("Broker stopped - failover transport interrupted", interrupted.await(5, TimeUnit.SECONDS));

            connection.close();
        }
    }

    @Test
    public void testZkDisconnectFastReconnect() throws Exception {
        for (int i=0;i<10;i++) {
            LOG.info("testZkDisconnectFastReconnect - Iteration:" + i);
            doTestZkDisconnectFastReconnect(false, "amq.xml");
        }
    }

    @Test
    public void testZkDisconnectFastReconnectDiskLock() throws Exception {
        for (int i=0;i<10;i++) {
            LOG.info("testZkDisconnectFastReconnectDiskLock - Iteration:" + i);
            doTestZkDisconnectFastReconnect(false, "amq-disk-lock.xml");
        }
    }

    @Test
    public void testZkDisconnectFastReconnectAfterStart() throws Exception {
        doTestZkDisconnectFastReconnect(true, "amq.xml");
    }

    private void doTestZkDisconnectFastReconnect(final boolean waitForInitalconnect, String xmlConfig) throws Exception {

        underTest = new ActiveMQServiceFactory();
        underTest.curator = curator;

        Properties props = new Properties();
        props.put("config", xmlConfig);
        props.put("broker-name", "amq");
        props.put("connectors", "openwire");

        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch zkInterruptionComplete = new CountDownLatch(1);

        final ActiveMQConnection[] amqConnection = new ActiveMQConnection[]{null};

        Executor workers = Executors.newCachedThreadPool();

        workers.execute(new Runnable() {
            @Override
            public void run() {

                if (waitForInitalconnect) {
                    try {
                        connected.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                for (int i = 0; i < 20; i++) {
                    // kill zk connections to force reconnect
                    standaloneServerFactory.closeAll();
                    try {
                        TimeUnit.MILLISECONDS.sleep(random.nextInt(300));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                }
                zkInterruptionComplete.countDown();
            }
        });

        workers.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("failover:(tcp://localhost:61616)?useExponentialBackOff=false&initialReconnectDelay=500");

                    amqConnection[0] = (ActiveMQConnection) cf.createConnection();

                    // will block till broker is started
                    amqConnection[0].start();
                    connected.countDown();


                } catch (JMSException e) {
                    e.printStackTrace();
                }

            }
        });


        underTest.updated("b", props);

        assertTrue("zk closeAll complete", zkInterruptionComplete.await(60, TimeUnit.SECONDS));

        LOG.info("Zk close all done!");

        assertTrue("was connected", connected.await(15, TimeUnit.SECONDS));

        for (int i = 0; i < 30; i++) {
            LOG.info("Checking for connection...");
            if (amqConnection[0].getTransport().isConnected()) {
                break;
            } else {
                TimeUnit.MILLISECONDS.sleep(500);
            }
        }
        assertTrue("is connected", amqConnection[0].getTransport().isConnected());

        amqConnection[0].close();
        underTest.destroy();
    }

    public static class IOLockTester extends AbstractLocker {
        public IOLockTester() {}

        static AtomicInteger failOnVal = new AtomicInteger(0);
        @Override
        public void configure(PersistenceAdapter persistenceAdapter) throws IOException {
            LOG.info("Configure: " + persistenceAdapter);
            lockAcquireSleepInterval=10;
        }

        @Override
        protected void doStop(ServiceStopper serviceStopper) throws Exception {}

        @Override
        protected void doStart() throws Exception {}

        @Override
        public boolean keepAlive() throws IOException {
            LOG.info("keepAlive: callCount " + failOnVal.get());
            int callCount = failOnVal.incrementAndGet();
            if (callCount > 1 && callCount < 5) {
                LOG.info("Failing keep alive");
                this.lockable.getBrokerService().handleIOException(new IOException("Blowing up on call count: " + callCount));
            }
            return true;
        }
    }

    @Test
    public void testStartRetryOnLockIOException() throws Exception {

        underTest = new ActiveMQServiceFactory();
        underTest.curator = curator;

        Properties props = new Properties();
        props.put("config", "amq-ioe-lock.xml");
        props.put("broker-name", "amq");
        props.put("connectors", "openwire");

        underTest.updated("b", props);

        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("failover:(tcp://localhost:61616)?useExponentialBackOff=false&timeout=15000");

        final ActiveMQConnection connection = (ActiveMQConnection) cf.createConnection();
        connection.start();

        assertTrue("is connected", connection.getTransport().isConnected());
    }

    @Test
    public void testStaticNetworkConnectors() throws Exception {
        final BrokerService brokerService = new BrokerService();
        final CountDownLatch connected = new CountDownLatch(1);

        brokerService.addConnector("tcp://localhost:55555");
        brokerService.setPersistent(false);
        brokerService.setBrokerName("temp-broker");
        brokerService.start();
        brokerService.waitUntilStarted();

        underTest = new ActiveMQServiceFactory();
        underTest.curator = curator;

        curator.blockUntilConnected();
        Properties props = new Properties();
        props.put("config","amq.xml");
        props.put("connectors","openwire");
        props.put("standalone","true");
        props.put("broker-name","network-broker");
        props.put("openwire-port","44444");

        props.put("static-network","tcp://localhost:55555");

        final ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("failover:(tcp://localhost:44444)?useExponentialBackOff=false&timeout=10000");
        final ActiveMQConnection connection = (ActiveMQConnection) cf.createConnection();

        try {
            underTest.updated("network-broker",props);
            connection.start();
            connected.countDown();
            brokerService.start();
            brokerService.waitUntilStarted();
            waitForClientsToConnect(brokerService, 1);
            Assert.assertTrue("No client",brokerService.getBroker().getClients().length>0);
        } catch (Exception e){
            throw  e;
        }
        finally {
            connection.close();
            underTest.destroy();
            brokerService.stop();
        }
    }

    @Test
    public void tesFabricNetworkConnectors() throws Exception {
        ActiveMQServiceFactory underTest2 = new ActiveMQServiceFactory();
        underTest = new ActiveMQServiceFactory();

        curator.blockUntilConnected();

        underTest.curator = curator;
        underTest2.curator = curator;

        Properties template = new Properties();
        template.put("kind","StandAlone");
        template.put("config","amq.xml");
        template.put("connectors","openwire");

        Properties b1Properties = new Properties(template);
        b1Properties.put("group","group-a");
        b1Properties.put("broker-name","a-broker");
        b1Properties.put("openwire-port","44444");
        b1Properties.put("container.ip","localhost");

        Properties b2Properties = new Properties(template);
        b2Properties.put("group","group-b");
        b2Properties.put("broker-name","b-broker");
        b2Properties.put("openwire-port","55555");
        b2Properties.put("network","group-a");
        b2Properties.put("container.ip","localhost");

        try {
            underTest.updated("broker.a", b1Properties);
            underTest2.updated("broker.b", b2Properties);

            BrokerService brokerA = null;
            while( brokerA==null ) {
                brokerA = underTest.getBrokerService("a-broker");
                Thread.sleep(100);
            }
            waitForClientsToConnect(brokerA, 1);
            Assert.assertTrue("No client",brokerA.getBroker().getClients().length>0);

        } catch (Exception e){
            throw  e;
        }
        finally {
            underTest.destroy();
            underTest2.destroy();
        }
    }


    @Test
    public void testThreadsOnTimeout() throws Exception {

        curator.close();

        SocketProxy socketProxy = new SocketProxy(new URI("tcp://localhost:" + zkPort));
        final CuratorFramework proxyCurator = CuratorFrameworkFactory.builder()
                .connectString("localhost:" + socketProxy.getUrl().getPort())
                .sessionTimeoutMs(5000)
                .connectionTimeoutMs(3000)
                .retryPolicy(new RetryNTimes(10, 1000))
                .build();
        proxyCurator.start();


        proxyCurator.getZookeeperClient().blockUntilConnectedOrTimedOut();
        LOG.info("curator is go: " + proxyCurator);


        final String path = "/singletons/test/threads" + System.currentTimeMillis() + "**";
        final ArrayList<Runnable> members = new ArrayList<Runnable>();
        final int nThreads = 1;

        class GroupRunnable implements Runnable, GroupListener<NodeState> {
            final int id;
            final private BlockingQueue<Integer> jobQueue = new LinkedBlockingDeque<Integer>();

            ZooKeeperGroup<NodeState> group;
            NodeState nodeState;

            public GroupRunnable(int id) {
                this.id = id;
                members.add(this);
                nodeState = new NodeState("foo" + id);
            }

            @Override
            public void run() {
                group = new ZooKeeperGroup<NodeState>(proxyCurator, path, NodeState.class);
                group.add(this);
                LOG.info("run: Added: " + this);

                try {
                    while (true) {
                        switch (jobQueue.take()) {
                            case 0:
                                LOG.info("run: close: " + this);
                                try {
                                    group.close();
                                } catch (IOException ignored) {
                                }
                                return;
                            case 1:
                                LOG.info("run: start: " + this);
                                group.start();
                                group.update(nodeState);
                                break;
                            case 2:
                                LOG.info("run: update: " + this);
                                nodeState.setId(nodeState.getId() + id);
                                group.update(nodeState);
                                break;

                        }
                    }
                } catch (InterruptedException exit) {
                }
            }

            @Override
            public void groupEvent(Group<NodeState> group, GroupEvent event) {

            }
        };

        ExecutorService executorService = Executors.newFixedThreadPool(nThreads);
        for (int i=0;i<nThreads;i++) {
            executorService.execute(new GroupRunnable(i));
        }

        for (Runnable r : members) {
            GroupRunnable groupRunnable = (GroupRunnable) r;
            groupRunnable.jobQueue.offer(1);

            // wait for registration
            while (groupRunnable.group == null || groupRunnable.group.getId() == null) {
                TimeUnit.MILLISECONDS.sleep(100);
            }
        }

        boolean firsStartedIsMaster = ((GroupRunnable) members.get(0)).group.isMaster();

        assertTrue("first started is master", firsStartedIsMaster);
        LOG.info("got master...");

        // lets see how long they take to notice a no responses to heart beats
        socketProxy.pause();

        // splash in an update
        for (Runnable r : members) {
            GroupRunnable groupRunnable = (GroupRunnable) r;
            groupRunnable.jobQueue.offer(2);
        }

        boolean hasMaster = true;
        while (hasMaster) {
            for (Runnable r : members) {
                GroupRunnable groupRunnable = (GroupRunnable) r;
                hasMaster &= groupRunnable.group.isMaster();
            }
            if (hasMaster) {
                LOG.info("Waiting for no master state on proxy pause");
                TimeUnit.SECONDS.sleep(1);
            }
        }


        for (Runnable r : members) {
            GroupRunnable groupRunnable = (GroupRunnable) r;
            groupRunnable.jobQueue.offer(0);
        }

        executorService.shutdown();
        // at a min when the session has expired
        boolean allThreadComplete = executorService.awaitTermination(6, TimeUnit.SECONDS);
        proxyCurator.close();
        socketProxy.close();
        assertTrue("all threads complete", allThreadComplete);

    }

    @Test
    public void testCallbackOnDisconnectCanClose() throws Exception {

        curator.close();

        LOG.info("....");
        SocketProxy socketProxy = new SocketProxy(new URI("tcp://localhost:" + zkPort));
        final CuratorFramework proxyCurator = CuratorFrameworkFactory.builder()
                .connectString("localhost:" + socketProxy.getUrl().getPort())
                .sessionTimeoutMs(5000)
                .connectionTimeoutMs(3000)
                .retryPolicy(new RetryNTimes(10, 1000))
                .build();
        proxyCurator.start();


        proxyCurator.getZookeeperClient().blockUntilConnectedOrTimedOut();
        LOG.info("curator is go: " + proxyCurator);


        final String path = "/singletons/test/threads" + System.currentTimeMillis() + "**";
        final ArrayList<Runnable> members = new ArrayList<Runnable>();
        final int nThreads = 1;

        final CountDownLatch gotDisconnectEvent = new CountDownLatch(1);
        class GroupRunnable implements Runnable, GroupListener<NodeState> {
            final int id;
            final private BlockingQueue<Integer> jobQueue = new LinkedBlockingDeque<Integer>();

            ZooKeeperGroup<NodeState> group;
            NodeState nodeState;

            public GroupRunnable(int id) {
                this.id = id;
                members.add(this);
                nodeState = new NodeState("foo" + id);
            }

            @Override
            public void run() {
                group = new ZooKeeperGroup<NodeState>(proxyCurator, path, NodeState.class);
                group.add(this);
                LOG.info("run: Added: " + this);

                try {
                    while (true) {
                        switch (jobQueue.take()) {
                            case 0:
                                LOG.info("run: close: " + this);
                                try {
                                    group.close();
                                } catch (IOException ignored) {
                                }
                                return;
                            case 1:
                                LOG.info("run: start: " + this);
                                group.start();
                                group.update(nodeState);
                                break;
                            case 2:
                                LOG.info("run: update: " + this);
                                nodeState.setId(nodeState.getId() + id);
                                group.update(nodeState);
                                break;

                        }
                    }
                } catch (InterruptedException exit) {
                }
            }

            @Override
            public void groupEvent(Group<NodeState> group, GroupEvent event) {
                LOG.info("Got: event: " + event);
                if (event.equals(GroupEvent.DISCONNECTED)) {
                    gotDisconnectEvent.countDown();
                }
            }
        };

        ExecutorService executorService = Executors.newFixedThreadPool(nThreads);
        for (int i=0;i<nThreads;i++) {
            executorService.execute(new GroupRunnable(i));
        }

        for (Runnable r : members) {
            GroupRunnable groupRunnable = (GroupRunnable) r;
            groupRunnable.jobQueue.offer(1);

            // wait for registration
            while (groupRunnable.group == null || groupRunnable.group.getId() == null) {
                TimeUnit.MILLISECONDS.sleep(100);
            }
        }

        boolean firsStartedIsMaster = ((GroupRunnable) members.get(0)).group.isMaster();

        assertTrue("first started is master", firsStartedIsMaster);
        LOG.info("got master...");

        // lets see how long they take to notice a no responses to heart beats
        socketProxy.pause();

        // splash in an update
        for (Runnable r : members) {
            GroupRunnable groupRunnable = (GroupRunnable) r;
            groupRunnable.jobQueue.offer(2);
        }

        boolean hasMaster = true;
        while (hasMaster) {
            for (Runnable r : members) {
                GroupRunnable groupRunnable = (GroupRunnable) r;
                hasMaster &= groupRunnable.group.isMaster();
            }
            if (hasMaster) {
                LOG.info("Waiting for no master state on proxy pause");
                TimeUnit.SECONDS.sleep(1);
            }
        }


        try {
            boolean gotDisconnect = gotDisconnectEvent.await(15, TimeUnit.SECONDS);

            assertTrue("got disconnect event", gotDisconnect);

            LOG.info("do close");
            for (Runnable r : members) {
                GroupRunnable groupRunnable = (GroupRunnable) r;
                groupRunnable.jobQueue.offer(0);
            }

            executorService.shutdown();
            // at a min when the session has expired
            boolean allThreadComplete = executorService.awaitTermination(6, TimeUnit.SECONDS);

            assertTrue("all threads complete", allThreadComplete);

        } finally {
            proxyCurator.close();
            socketProxy.close();
        }
    }


    @Test
    public void testDiscoveryOnRestartCurator() throws Exception {

        underTest = new ActiveMQServiceFactory();
        underTest.curator = curator;

        Properties props = new Properties();
        props.put("config", "amq.xml");
        props.put("broker-name", "amq");
        props.put("group", "amq");
        props.put("connectors", "openwire");
        props.put("openwire-port", "0");
        props.put("container.ip", "localhost");

        underTest.updated("b", props);

        final AtomicReference<CuratorFramework> curatorFrameworkAtomicReference = new AtomicReference<>(curator);

        OsgiFabricDiscoveryAgent osgiFabricDiscoveryAgent = new OsgiFabricDiscoveryAgent(new BundleContext() {
            @Override
            public String getProperty(String s) {
                return null;
            }

            @Override
            public Bundle getBundle() {
                return null;
            }

            @Override
            public Bundle installBundle(String s, InputStream inputStream) throws BundleException {
                return null;
            }

            @Override
            public Bundle installBundle(String s) throws BundleException {
                return null;
            }

            @Override
            public Bundle getBundle(long l) {
                return null;
            }

            @Override
            public Bundle[] getBundles() {
                return new Bundle[0];
            }

            @Override
            public void addServiceListener(ServiceListener serviceListener, String s) throws InvalidSyntaxException {}

            @Override
            public void addServiceListener(ServiceListener serviceListener) {

            }

            @Override
            public void removeServiceListener(ServiceListener serviceListener) {}

            @Override
            public void addBundleListener(BundleListener bundleListener) {}

            @Override
            public void removeBundleListener(BundleListener bundleListener) {}

            @Override
            public void addFrameworkListener(FrameworkListener frameworkListener) {}

            @Override
            public void removeFrameworkListener(FrameworkListener frameworkListener) {}

            @Override
            public ServiceRegistration<?> registerService(String[] strings, Object o, Dictionary<String, ?> dictionary) {
                return null;
            }

            @Override
            public ServiceRegistration<?> registerService(String s, Object o, Dictionary<String, ?> dictionary) {
                return null;
            }

            @Override
            public <S> ServiceRegistration<S> registerService(Class<S> aClass, S s, Dictionary<String, ?> dictionary) {
                return null;
            }

            @Override
            public ServiceReference<?>[] getServiceReferences(String s, String s1) throws InvalidSyntaxException {
                return new ServiceReference<?>[0];
            }

            @Override
            public ServiceReference<?>[] getAllServiceReferences(String s, String s1) throws InvalidSyntaxException {
                return new ServiceReference<?>[0];
            }

            @Override
            public ServiceReference<?> getServiceReference(String s) {
                return null;
            }

            @Override
            public <S> ServiceReference<S> getServiceReference(Class<S> aClass) {
                return null;
            }

            @Override
            public <S> Collection<ServiceReference<S>> getServiceReferences(Class<S> aClass, String s) throws InvalidSyntaxException {
                return null;
            }

            @Override
            public <S> S getService(ServiceReference<S> serviceReference) {
                return (S) curatorFrameworkAtomicReference.get();
            }

            @Override
            public boolean ungetService(ServiceReference<?> serviceReference) {
                return false;
            }

            @Override
            public File getDataFile(String s) {
                return null;
            }

            @Override
            public Filter createFilter(String s) throws InvalidSyntaxException {
                return null;
            }

            @Override
            public Bundle getBundle(String s) {
                return null;
            }
        });

        final LinkedBlockingQueue<DiscoveryEvent> discoveryEvents = new LinkedBlockingQueue<DiscoveryEvent>(10);
        osgiFabricDiscoveryAgent.setDiscoveryListener(new DiscoveryListener() {
            @Override
            public void onServiceAdd(DiscoveryEvent discoveryEvent) {
                discoveryEvents.offer(discoveryEvent);
            }

            @Override
            public void onServiceRemove(DiscoveryEvent discoveryEvent) {}
        });

        // will call into dummy bundle and get curator
        osgiFabricDiscoveryAgent.addingService(null);

        osgiFabricDiscoveryAgent.setGroupName("amq");
        osgiFabricDiscoveryAgent.start();

        DiscoveryEvent event = discoveryEvents.poll(5, TimeUnit.SECONDS);
        LOG.info("event: " + event);
        assertNotNull("got added service", event);

        underTest.deleted("b");

        // swap curator ref
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString("localhost:" + zkPort)
                .sessionTimeoutMs(15000)
                .retryPolicy(new RetryNTimes(5000, 1000));

        curator = builder.build();
        LOG.debug("Starting new curator " + curator);
        curator.start();

        curatorFrameworkAtomicReference.get().close();
        curatorFrameworkAtomicReference.set(curator);

        // will call into dummy bundle and get new curator ref
        osgiFabricDiscoveryAgent.addingService(null);

        // start broker again
        underTest.curator = curator;
        underTest.updated("b", props);

        event = discoveryEvents.poll(5, TimeUnit.SECONDS);
        LOG.info("new event: " + event);
        assertNotNull("got newly added service", event);

        underTest.deleted("b");
    }

    private void waitForClientsToConnect(BrokerService broker, int clientCount) throws Exception {
        for(int i=0;i<10;i++){
            if(broker.getBroker().getClients().length < clientCount)
                Thread.sleep(500);
            else
                break;
        }
    }
}
