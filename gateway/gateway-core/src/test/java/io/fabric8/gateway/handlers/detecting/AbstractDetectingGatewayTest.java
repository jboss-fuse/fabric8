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
package io.fabric8.gateway.handlers.detecting;

import io.fabric8.gateway.AbstractMqGatewayTest;
import io.fabric8.gateway.CallDetailRecord;
import io.fabric8.gateway.ServiceDTO;
import io.fabric8.gateway.ServiceMap;
import io.fabric8.gateway.handlers.detecting.protocol.ssl.SslConfig;
import io.fabric8.gateway.handlers.http.HttpGateway;
import io.fabric8.gateway.handlers.http.HttpGatewayHandler;
import io.fabric8.gateway.handlers.http.HttpGatewayServer;
import io.fabric8.gateway.handlers.http.HttpMappingRule;
import io.fabric8.gateway.handlers.http.MappedServices;
import io.fabric8.gateway.loadbalancer.LoadBalancer;
import io.fabric8.gateway.loadbalancer.RoundRobinLoadBalancer;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.apollo.broker.Broker;
import org.apache.activemq.apollo.util.ServiceControl;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.qpid.amqp_1_0.jms.impl.ConnectionFactoryImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.VertxFactory;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.http.HttpServer;
import org.vertx.java.core.http.HttpServerRequest;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.MessageProducer;
import javax.jms.Session;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public abstract class AbstractDetectingGatewayTest extends AbstractMqGatewayTest {
    private static final transient Logger LOG = LoggerFactory.getLogger(DetectingGatewayVirtualHostTest.class);
    // Setup some brokers.
    final protected ArrayList<Broker> brokers = new ArrayList<Broker>();
    final protected ArrayList<DetectingGateway> gateways = new ArrayList<DetectingGateway>();
    // Setup Vertx
    protected Vertx vertx;
    ServiceMap serviceMap = new ServiceMap();
    // used to swap threads since Vertx.stop() does funky stuff.
    // see https://github.com/vert-x/mod-lang-clojure/commit/fa6f78874a0c3507955dc5743f833cfbbbb60cb5
    Thread current = null;
    //setup http gateway and endpoints
    final HashMap<String, MappedServices> mappedServices = new HashMap<String, MappedServices>();
    HttpServer restEndpointServer;
    HttpGatewayServer httpGatewayServer;

    @Before
    public void startVertx() {
        current = Thread.currentThread();
        if( vertx == null ) {
            vertx = VertxFactory.newVertx();
        }
    }

    @After
    public void stopVertx(){
        ClassLoader tccl = current.getContextClassLoader();
        if( vertx!=null ) {
            vertx.stop();
            vertx = null;
            current.setContextClassLoader(tccl);
        }
    }

    @Before
    public void startBrokers() {
        for(int i=0; i < 2; i++) {

            // create a broker..
            String name = "broker";
            Broker broker = createBroker(name);
            ServiceControl.start(broker);
            brokers.add(broker);

            // Add a service map entry for the broker.
            ServiceDTO details = new ServiceDTO();
            details.setId(name+i);
            details.setVersion("1.0");
            details.setContainer("testing");
            details.setBundleName("none");
            details.setBundleVersion("1.0");
            List<String> services = Arrays.asList(
                "stomp://localhost:" + portOfBroker(i),
                "mqtt://localhost:" + portOfBroker(i),
                "amqp://localhost:" + portOfBroker(i),
                "tcp://localhost:" + portOfBroker(i)
            );
            details.setServices(services);
            serviceMap.serviceUpdated(name, details);

            println(String.format("Broker %s is exposing: %s", name, services));
        }
    }

    @After
    public void stopBrokers() {
        for (Broker broker : brokers) {
            ServiceControl.stop(broker);
        }
        brokers.clear();
    }

    @After
    public void stopGateways() {
        for (DetectingGateway gateway : gateways) {
            gateway.destroy();
        }
        gateways.clear();
    }

    @After
    public void endRestEndpoint() throws InterruptedException {
        if( restEndpointServer !=null ) {
            restEndpointServer.close();
            restEndpointServer = null;
        }
    }

    @After
    public void stopHttpGateway(){
        if( httpGatewayServer!=null ) {
            httpGatewayServer.destroy();
            httpGatewayServer = null;
        }
    }

    public HttpServer startRestEndpoint() throws InterruptedException {
        restEndpointServer = vertx.createHttpServer();
        restEndpointServer.requestHandler(new Handler<HttpServerRequest>() {
            @Override
            public void handle(HttpServerRequest request) {
                request.response().putHeader("content-type", "text/plain");
                request.response().end("Hello: "+request.query());
            }
        });

        FutureHandler<AsyncResult<HttpServer>> future = new FutureHandler<>();
        restEndpointServer.listen(8181, "0.0.0.0", future);
        future.await();
        return restEndpointServer;
    }

    public HttpGatewayServer startHttpGateway() {

        if( restEndpointServer!=null ) {
            LoadBalancer loadBalancer=new RoundRobinLoadBalancer();

            ServiceDTO serviceDetails = new ServiceDTO();
            serviceDetails.setContainer("local");
            serviceDetails.setVersion("1");

            mappedServices.put("/hello/world", new MappedServices("http://localhost:8181", serviceDetails, loadBalancer, false));
        }

        HttpGatewayHandler handler = new HttpGatewayHandler(vertx, new HttpGateway(){
            @Override
            public void addMappingRuleConfiguration(HttpMappingRule mappingRule) {
            }

            @Override
            public void removeMappingRuleConfiguration(HttpMappingRule mappingRule) {
            }

            @Override
            public Map<String, MappedServices> getMappedServices() {
                return mappedServices;
            }

            @Override
            public boolean isEnableIndex() {
                return true;
            }

            @Override
            public InetSocketAddress getLocalAddress() {
                return new InetSocketAddress("0.0.0.0", 8080);
            }

            @Override
            public void addCallDetailRecord(CallDetailRecord cdr) {
            }
        });
        httpGatewayServer = new HttpGatewayServer(vertx, handler, null, 8080);
        httpGatewayServer.setHost("localhost");
        httpGatewayServer.init();
        return httpGatewayServer;
    }

    int portOfBroker(int broker) {
        return ((InetSocketAddress)brokers.get(broker).get_socket_address()).getPort();
    }

    protected void println(Object msg) {
        LOG.info(msg.toString());
    }

    void assertConnectedToBroker(int broker) {
        for( int i = 0; i < brokers.size(); i++) {
            if( i==broker ) {
                assertEquals(1, getConnectionsOnBroker(i));
            } else {
                assertEquals(0, getConnectionsOnBroker(i));
            }
        }
    }

    private int getConnectionsOnBroker(int brokerIdx) {
        return brokers.get(brokerIdx).connections().size();
    }

    private <T> T within(int timeout, TimeUnit unit, Callable<T> action) throws Exception {
        long remaining = unit.toMillis(timeout);
        Throwable lastError=null;
        long step = remaining/10;
        do  {
            long start = System.currentTimeMillis();
            try {
                return action.call();
            } catch (Throwable e) {
                lastError = e;
            }
            long duration = System.currentTimeMillis()-start;
            remaining -= duration;
            if( duration < step ) {
                long nap = step - duration;
                remaining -= duration;
                if( remaining > 0 ) {
                    Thread.sleep(nap);
                }
            }
        } while(remaining > 0);
        if( lastError instanceof Exception ) {
            throw (Exception)lastError;
        }
        if( lastError instanceof Error ) {
            throw (Error)lastError;
        }
        throw new RuntimeException(lastError);
    }

    public abstract DetectingGateway createGateway();

    // This test is not yet ready for prime time..
    @Test
    public void canDetectTheAMQPProtocol() throws Exception {
        DetectingGateway gateway = createGateway();
        gateway.init();
        final ConnectionFactoryImpl factory = new ConnectionFactoryImpl("localhost", gateway.getBoundPort(), "admin", "password");
        Connection connection = factory.createConnection();
        connection.start();

        assertEquals(1, gateway.getSuccessfulConnectionAttempts());
        assertEquals(1, gateway.getConnectedClients().length);

        // We can't get the virtual host from AMQP connections yet, so the connection
        // Should get routed to the default virtual host which is broker 1.
        assertConnectedToBroker(1);
        connection.close();
    }

    @Test
    public void canDetectTheOpenwireSslProtocol() throws Exception {

        System.setProperty("javax.net.ssl.trustStore", new File(basedir(), "src/test/resources/client.ks").getCanonicalPath());
        System.setProperty("javax.net.ssl.trustStorePassword", "password");
        System.setProperty("javax.net.ssl.trustStoreType", "jks");

        DetectingGateway gateway = createGateway();
        gateway.init();

        String url = "ssl://localhost:" + gateway.getBoundPort()+"?wireFormat.host=broker";
        final ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
        Connection connection = factory.createConnection();
        connection.start();

        assertEquals(1, gateway.getSuccessfulConnectionAttempts());
        assertEquals(1, gateway.getConnectedClients().length);

        Thread.sleep(6000);

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination test = new ActiveMQQueue("TEST");
        MessageProducer producer = session.createProducer(test);
        producer.send(session.createTextMessage("Hello"));

        connection.close();

    }

    @Test
    public void canDetectHttpSslProtocol() throws Exception {

        System.setProperty("javax.net.ssl.trustStore", new File(basedir(), "src/test/resources/client.ks").getCanonicalPath());
        System.setProperty("javax.net.ssl.trustStorePassword", "password");
        System.setProperty("javax.net.ssl.trustStoreType", "jks");

        startRestEndpoint();
        startHttpGateway();
        DetectingGateway gateway = createGateway();
        gateway.init();
        gateway.setHttpGateway(new InetSocketAddress(httpGatewayServer.getHost(), httpGatewayServer.getPort()));

        final FutureHandler<HttpClientResponse> future = new FutureHandler<>();
        vertx.createHttpClient().setSSL(true).setHost("localhost").setPort(gateway.getBoundPort()).get("/hello/world?wsdl", new Handler<HttpClientResponse>() {
            @Override
            public void handle(HttpClientResponse event) {
                future.handle(event);
            }
        }).end();

        HttpClientResponse response = future.await();

        assertEquals( 200, response.statusCode() );

    }

    @Test
    public void exceptionHttpSslProtocolENTESB9517() throws Exception {
        //XXX: the idea is that this test should throw exceptions but must terminate
        System.setProperty("javax.net.ssl.trustStore", new File(basedir(), "src/test/resources/client.ks").getCanonicalPath());
        System.setProperty("javax.net.ssl.trustStorePassword", "password");
        System.setProperty("javax.net.ssl.trustStoreType", "jks");

        startRestEndpoint();
        startHttpGateway();
        DetectingGateway gateway = createGateway();
        SslConfig sslconfig = new SslConfig(new File(basedir(), "src/test/resources/client.ks"), "password");
        sslconfig.setEnabledCipherSuites("TLS_ECDHE_ECDSA_WITH_NULL_SHA");
        sslconfig.setKeyPassword("password");

        gateway.setSslConfig(sslconfig);
        gateway.init();
        gateway.setHttpGateway(new InetSocketAddress(httpGatewayServer.getHost(), httpGatewayServer.getPort()));

        for(int i = 0; i<100; i++) {
            final FutureHandler<HttpClientResponse> future1 = new FutureHandler<>();
            vertx.createHttpClient().setConnectTimeout(0).setSSL(true).setHost("localhost")
                    .setPort(gateway.getBoundPort()).exceptionHandler(new Handler<Throwable>() {
                @Override
                public void handle(Throwable throwable) {
                    LOG.info("this is expected but the test must terminate: Exception: " + throwable.getMessage());
                    future1.handle(null);
                }
            })
                    .get("/hello/world?wsdl", new Handler<HttpClientResponse>() {
                        @Override
                        public void handle(HttpClientResponse event) {
                            future1.handle(event);
                        }
                    }).end();

            HttpClientResponse response1 = future1.await();
            assertEquals(null, response1);
        }

    }

    /**
     * Invlaid protocols should get quickly rejected.
     * @throws Exception
     */
    @Test
    public void rejectsInvalidProtocols() throws Exception {

        final DetectingGateway gateway = createGateway();
        gateway.init();
        final Socket socket = new Socket("localhost", gateway.getBoundPort());
        final OutputStream outputStream = socket.getOutputStream();

        within(2, TimeUnit.SECONDS, new Callable<Object>(){
            @Override
            public Object call() throws Exception {
                assertEquals(1, gateway.getConnectingClients().length);
                return null;
            }
        });
        within(2, TimeUnit.SECONDS, new Callable<Object>(){
            @Override
            public Object call() throws Exception {
                try {
                    outputStream.write("Hello World!\n".getBytes());
                    fail("Expected exception.");
                } catch (IOException e) {
                }
                return null;
            }
        });
        socket.close();

        assertEquals(1, gateway.getReceivedConnectionAttempts());
        assertEquals(1, gateway.getFailedConnectionAttempts());
        assertEquals(0, gateway.getSuccessfulConnectionAttempts());
        assertEquals(0, gateway.getConnectingClients().length);
        assertEquals(0, gateway.getConnectedClients().length);

    }

    /**
     * If a client comes in there should a time limit on how long
     * we keep a connection open while we are protocol detecting.
     *
     * @throws Exception
     */
    @Test
    public void timesOutConnectionAttempts() throws Exception {

        final DetectingGateway gateway = createGateway();
        gateway.init();
        final Socket socket = new Socket("localhost", gateway.getBoundPort());
        final InputStream inputStream = socket.getInputStream();

        long start = System.currentTimeMillis();
        socket.getOutputStream().write("STOMP".getBytes());
        assertEquals(-1, inputStream.read()); // Waits for the EOF
        long duration = System.currentTimeMillis() - start;
        socket.close();

        // The read should have blocked until the connection timeout occurs
        // at 5000 ms mark..
        assertTrue(duration > 4000);
        assertTrue(duration < 6000);

        within(1, TimeUnit.SECONDS, new Callable<Object>(){
            @Override
            public Object call() throws Exception {
                assertEquals(1, gateway.getReceivedConnectionAttempts());
                assertEquals(1, gateway.getFailedConnectionAttempts());
                assertEquals(0, gateway.getSuccessfulConnectionAttempts());
                assertEquals(0, gateway.getConnectingClients().length);
                assertEquals(0, gateway.getConnectedClients().length);
                return null;
            }
        });

    }

}
