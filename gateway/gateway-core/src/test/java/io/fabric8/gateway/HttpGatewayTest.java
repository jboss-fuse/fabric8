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


import io.fabric8.gateway.handlers.detecting.FutureHandler;
import io.fabric8.gateway.handlers.http.HttpGateway;
import io.fabric8.gateway.handlers.http.HttpGatewayHandler;
import io.fabric8.gateway.handlers.http.HttpGatewayServer;
import io.fabric8.gateway.handlers.http.HttpMappingRule;
import io.fabric8.gateway.handlers.http.MappedServices;
import io.fabric8.gateway.loadbalancer.LoadBalancer;
import io.fabric8.gateway.loadbalancer.RoundRobinLoadBalancer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.VertxFactory;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.http.HttpServer;
import org.vertx.java.core.http.HttpServerRequest;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 */
public class HttpGatewayTest {

    private static final transient Logger LOG = LoggerFactory.getLogger(HttpGatewayTest.class);

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

    HttpServer restEndpointServer;
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

    @After
    public void endRestEndpoint() throws InterruptedException {
        if( restEndpointServer !=null ) {
            restEndpointServer.close();
            restEndpointServer = null;
        }
    }
    protected void println(Object msg) {
        LOG.info(msg.toString());
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

    @After
    public void stopHttpGateway(){
        if( httpGatewayServer!=null ) {
            httpGatewayServer.destroy();
            httpGatewayServer = null;
        }
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

    /**
     * Validates that query params are passed to the proxied service.
     * Used to verify that ENTESB-5437 is fixed.
     *
     * @throws Exception
     */
    @Test
    public void testENTESB5437() throws Exception {

        startRestEndpoint();
        startHttpGateway();

        System.out.println("Requesting...");

        final FutureHandler<String> future = new FutureHandler<>();
        vertx.createHttpClient().setHost("localhost").setPort(8080).get("/hello/world?wsdl", new Handler<HttpClientResponse>() {
            @Override
            public void handle(HttpClientResponse event) {
                event.bodyHandler(new Handler<Buffer>() {
                    @Override
                    public void handle(Buffer event) {
                        future.handle(event.toString());
                    }
                });
            }
        }).end();
        assertEquals( "Hello: wsdl", future.await());

        stopHttpGateway();
        stopVertx();
    }

}
