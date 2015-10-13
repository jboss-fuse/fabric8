/**
 * Copyright 2005-2015 Red Hat, Inc.
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
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.jms.JMSException;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.After;
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

    @Before
    public void infraUp() throws Exception {
        int tickTime = 500;
        int numConnections = 5000;
        File dir = new File("target", "zookeeper" + random.nextInt()).getAbsoluteFile();

        ZooKeeperServer server = new ZooKeeperServer(dir, dir, tickTime);
        standaloneServerFactory = createFactory(0, numConnections);
        int zkPort = standaloneServerFactory.getLocalPort();

        standaloneServerFactory.startup(server);

        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString("localhost:" + zkPort)
                .retryPolicy(new RetryNTimes(5000, 1000));

        curator = builder.build();
        LOG.debug("Starting curator " + curator);
        curator.start();
    }

    @After
    public void infraDown() throws Exception {
        curator.close();
        standaloneServerFactory.shutdown();
    }

    @Test
    public void testZkDisconnectFastReconnect() throws Exception {
        doTestZkDisconnectFastReconnect(false);
    }

    @Test
    public void testZkDisconnectFastReconnectAfterStart() throws Exception {
        doTestZkDisconnectFastReconnect(true);
    }

    private void doTestZkDisconnectFastReconnect(final boolean waitForInitalconnect) throws Exception {

        underTest = new ActiveMQServiceFactory();
        underTest.curator = curator;

        Properties props = new Properties();
        props.put("config", "amq.xml");
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
                        connected.await(5, TimeUnit.SECONDS);
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
                    ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("failover:(tcp://localhost:61616)?useExponentialBackOff=false");

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

        assertTrue("zk closeAll complete", zkInterruptionComplete.await(20, TimeUnit.SECONDS));

        LOG.info("Zk close all done!");

        assertTrue("was connected", connected.await(20, TimeUnit.SECONDS));

        for (int i = 0; i < 20; i++) {
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

}