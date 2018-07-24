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
package io.fabric8.gateway;


import io.fabric8.common.util.ShutdownTracker;
import io.fabric8.gateway.handlers.detecting.DetectingGateway;
import io.fabric8.gateway.handlers.detecting.DetectingGatewayWebSocketHandler;
import io.fabric8.gateway.handlers.detecting.FutureHandler;
import io.fabric8.gateway.handlers.detecting.Protocol;
import io.fabric8.gateway.handlers.detecting.protocol.amqp.AmqpProtocol;
import io.fabric8.gateway.handlers.detecting.protocol.http.HttpProtocol;
import io.fabric8.gateway.handlers.detecting.protocol.mqtt.MqttProtocol;
import io.fabric8.gateway.handlers.detecting.protocol.openwire.OpenwireProtocol;
import io.fabric8.gateway.handlers.detecting.protocol.ssl.SslConfig;
import io.fabric8.gateway.handlers.detecting.protocol.ssl.SslProtocol;
import io.fabric8.gateway.handlers.detecting.protocol.stomp.StompProtocol;
import io.fabric8.gateway.handlers.http.HttpGateway;
import io.fabric8.gateway.handlers.http.HttpGatewayHandler;
import io.fabric8.gateway.handlers.http.HttpGatewayServer;
import io.fabric8.gateway.handlers.http.HttpMappingRule;
import io.fabric8.gateway.handlers.http.MappedServices;
import io.fabric8.gateway.loadbalancer.LoadBalancer;
import io.fabric8.gateway.loadbalancer.LoadBalancers;
import io.fabric8.gateway.loadbalancer.RoundRobinLoadBalancer;
import org.apache.activemq.apollo.broker.Broker;
import org.apache.activemq.apollo.dto.AcceptingConnectorDTO;
import org.apache.activemq.apollo.dto.BrokerDTO;
import org.apache.activemq.apollo.dto.VirtualHostDTO;
import org.apache.activemq.apollo.util.ServiceControl;
import org.fusesource.stomp.jms.StompJmsConnectionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.VertxFactory;
import org.vertx.java.core.http.HttpServer;
import org.vertx.java.core.http.HttpServerRequest;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 */
public class DetectingGatewayShutdownTest {

    private static final transient Logger LOG = LoggerFactory.getLogger(DetectingGatewayShutdownTest.class);
    ServiceMap serviceMap = new ServiceMap();

    // Setup Vertx
    protected Vertx vertx;
    @Before
    public void startVertx() {
        if( vertx == null ) {
            vertx = VertxFactory.newVertx();
        }
    }
    @After
    public void stopVertx(){
        if( vertx!=null ) {
            vertx.stop();
            vertx = null;
        }
    }

    // Setup some brokers.
    final protected ArrayList<Broker> brokers = new ArrayList<Broker>();
    final protected ArrayList<DetectingGateway> gateways = new ArrayList<DetectingGateway>();

    @Before
    public void startBrokers() {
        for(int i=0; i < 2; i++) {

            // create a broker..
            String name = "broker" + i;
            Broker broker = createBroker(name);
            ServiceControl.start(broker);
            brokers.add(broker);

            // Add a service map entry for the broker.
            ServiceDTO details = new ServiceDTO();
            details.setId(name);
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

    HttpServer restEndpointServer;
    public HttpServer startRestEndpoint() throws InterruptedException {
        restEndpointServer = vertx.createHttpServer();
        restEndpointServer.requestHandler(new Handler<HttpServerRequest>() {
            @Override
            public void handle(HttpServerRequest request) {
                request.response().putHeader("content-type", "text/plain");
                request.response().end("Hello World!");
            }
        });

        FutureHandler<AsyncResult<HttpServer>> future = new FutureHandler<>();
        restEndpointServer.listen(8181, "0.0.0.0", future);
        future.await();
        return restEndpointServer;
    }

    @After
    public void endRestEndpoint() throws InterruptedException {
        if( restEndpointServer !=null ) {
            restEndpointServer.close();
            restEndpointServer = null;
        }
    }

    @After
    public void stopBrokers() {
        for (Broker broker : brokers) {
            ServiceControl.stop(broker);
        }
        brokers.clear();
    }

    int portOfBroker(int broker) {
        return ((InetSocketAddress)brokers.get(broker).get_socket_address()).getPort();
    }

    public Broker createBroker(String hostname) {
        Broker broker = new Broker();
        BrokerDTO config = broker.config();

        // Configure the virtual host..
        VirtualHostDTO virtualHost = new VirtualHostDTO();
        virtualHost.id = hostname;
        virtualHost.host_names.add(hostname);
        config.virtual_hosts.add(virtualHost);

        // Configure the connectors
        AcceptingConnectorDTO connector = new AcceptingConnectorDTO();
        connector.connection_limit = 100;
        connector.bind = "tcp://0.0.0.0:0";
        config.connectors.clear();
        config.connectors.add(connector);

        return broker;
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

    final HashMap<String, MappedServices> mappedServices = new HashMap<String, MappedServices>();

    HttpGatewayServer httpGatewayServer;
    public HttpGatewayServer startHttpGateway() {


        if( restEndpointServer!=null ) {
            LoadBalancer loadBalancer=new RoundRobinLoadBalancer();

            ServiceDTO serviceDetails = new ServiceDTO();
            serviceDetails.setContainer("local");
            serviceDetails.setVersion("1");

            mappedServices.put("/hello/world", new MappedServices("http://localhost:8181", serviceDetails, loadBalancer, false));
        }

        DetectingGatewayWebSocketHandler websocketHandler = new DetectingGatewayWebSocketHandler();
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
        websocketHandler.setPathPrefix("");
        httpGatewayServer = new HttpGatewayServer(vertx, handler, websocketHandler, 8080);
        httpGatewayServer.setHost("localhost");
        httpGatewayServer.init();
        return httpGatewayServer;
    }

    @After
    public void stopHttpGateway(){
        if( httpGatewayServer!=null ) {
            httpGatewayServer.destroy();
            httpGatewayServer = null;
        }
    }

    public DetectingGateway startDetectingGateway() {

        String loadBalancerType = LoadBalancers.STICKY_LOAD_BALANCER;
        int stickyLoadBalancerCacheSize = LoadBalancers.STICKY_LOAD_BALANCER_DEFAULT_CACHE_SIZE;

        LoadBalancer serviceLoadBalancer = LoadBalancers.createLoadBalancer(loadBalancerType, stickyLoadBalancerCacheSize);

        ArrayList<Protocol> protocols = new ArrayList<Protocol>();
        protocols.add(new StompProtocol());
        protocols.add(new MqttProtocol());
        protocols.add(new AmqpProtocol());
        protocols.add(new OpenwireProtocol());
        protocols.add(new HttpProtocol());
        protocols.add(new SslProtocol());
        DetectingGateway gateway = new DetectingGateway();
        gateway.setPort(0);
        gateway.setVertx(vertx);
        SslConfig sslConfig = new SslConfig(new File(basedir(), "src/test/resources/server.ks"), "password");
        sslConfig.setKeyPassword("password");
        gateway.setSslConfig(sslConfig);
        gateway.setServiceMap(serviceMap);
        gateway.setProtocols(protocols);
        gateway.setServiceLoadBalancer(serviceLoadBalancer);
        gateway.setDefaultVirtualHost("broker1");
        gateway.setConnectionTimeout(5000);
        if( httpGatewayServer!=null ) {
            gateway.setHttpGateway(new InetSocketAddress("localhost", httpGatewayServer.getPort()));
        }
        gateway.init();

        gateways.add(gateway);
        return gateway;
    }


    @After
    public void stopGateways() {
        for (DetectingGateway gateway : gateways) {
            try {
                gateway.destroy();
            } catch (Throwable e) {
                LOG.info("Gateway {} already destroyed", gateway);
            }
        }
        gateways.clear();
    }


    protected File basedir() {
        try {
          File file = new File(getClass().getProtectionDomain().getCodeSource().getLocation().getFile());
          file = file.getParentFile().getParentFile().getCanonicalFile();
          if( file.isDirectory() ) {
              return file.getCanonicalFile();
          } else {
              return new File(".").getCanonicalFile();
          }
        } catch (Throwable e){
            return new File(".");
        }
    }

    @Test
    public void lotsOfClientLoad() throws Exception {

        startRestEndpoint();
        startHttpGateway();
        DetectingGateway gateway = startDetectingGateway();

        final ShutdownTracker tracker = new ShutdownTracker();

        // Run some concurrent load against the broker via the gateway...
        final StompJmsConnectionFactory factory = new StompJmsConnectionFactory();
        factory.setBrokerURI("tcp://localhost:"+gateway.getBoundPort());

        for(int client=0; client<30; client++) {
            final Random r = new Random();
            final int Low = 500;
            final int High = 10001;
            new Thread("JMS Client: "+client) {
                @Override
                public void run() {
                    while(tracker.attemptRetain()) {
                        try {
                            Connection connection = factory.createConnection();
                            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                            MessageConsumer consumer = session.createConsumer(session.createTopic("FOO"));
                            MessageProducer producer = session.createProducer(session.createTopic("FOO"));
                            producer.send(session.createTextMessage("Hello"));
                            consumer.receive(1000);
                            Thread.sleep(r.nextInt(High-Low) + Low);
                            connection.close();
                        } catch (JMSException e) {
                            e.printStackTrace();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        } finally {
                            tracker.release();
                        }
                    }
                }
            }.start();
        }

        Thread.sleep(20000);
        gateway.destroy();
        tracker.stop();

    }

}
