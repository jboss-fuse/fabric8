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
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.http.HttpServer;
import org.vertx.java.core.http.HttpServerRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

/**
 */
public class HttpGatewayConnectionTimeoutTest extends AbstractHttpGatewayTest {

    private static final transient Logger LOG = LoggerFactory.getLogger(HttpGatewayConnectionTimeoutTest.class);

    @Override
    public HttpServer startRestEndpoint() throws InterruptedException {
        restEndpointServer = vertx.createHttpServer();
        return restEndpointServer;
    }

    @Override
    public HttpGatewayServer startHttpGateway() {

        if( restEndpointServer!=null ) {
            LoadBalancer loadBalancer=new RoundRobinLoadBalancer();

            ServiceDTO serviceDetails = new ServiceDTO();
            serviceDetails.setContainer("local");
            serviceDetails.setVersion("1");

            //XXX: pick a non routable address to simulate connection refused (in this case 10.0.0.0 )
            mappedServices.put("/hello/world", new MappedServices("http://10.0.0.0:8181", serviceDetails, loadBalancer, false));
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
        handler.setConnectionTimeout(1000);
        httpGatewayServer = new HttpGatewayServer(vertx, handler, null, 8080);
        httpGatewayServer.setHost("localhost");
        httpGatewayServer.init();
        return httpGatewayServer;
    }

    @Test
    public void testConnectionTimeout() throws Exception {
        startRestEndpoint();
        startHttpGateway();

        LOG.info("Requesting...");

        final FutureHandler<HttpClientResponse> future = new FutureHandler<>();
        vertx.createHttpClient().setHost("localhost").setPort(8080).get("/hello/world?wsdl", new Handler<HttpClientResponse>() {
            @Override
            public void handle(HttpClientResponse event) {
                future.handle(event);
            }
        }).end();

        HttpClientResponse response = future.await();

        assertEquals( response.statusCode(), 504 );

        stopHttpGateway();
        stopVertx();
    }

}
