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
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.jms.JMSException;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static org.apache.zookeeper.server.ServerCnxnFactory.createFactory;
import static org.junit.Assert.assertTrue;

public class ServiceFactoryTest {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceFactoryTest.class);

    ServerCnxnFactory standaloneServerFactory;
    CuratorFramework curator;
    ActiveMQServiceFactory underTest;
    Random random = new Random();
    ZooKeeperServer server;

    @Before
    public void infraUp() throws Exception {
        int tickTime = 500;
        int numConnections = 5000;
        File dir = new File("target", "zookeeper" + random.nextInt()).getAbsoluteFile();

        server = new ZooKeeperServer(dir, dir, tickTime);
        standaloneServerFactory = createFactory(0, numConnections);
        int zkPort = standaloneServerFactory.getLocalPort();

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

        assertTrue("was connected", connected.await(25, TimeUnit.SECONDS));

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

    private void waitForClientsToConnect(BrokerService broker, int clientCount) throws Exception {
        for(int i=0;i<10;i++){
            if(broker.getBroker().getClients().length < clientCount)
                Thread.sleep(500);
            else
                break;
        }
    }
}
