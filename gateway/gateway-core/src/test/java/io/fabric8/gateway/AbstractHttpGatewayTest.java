package io.fabric8.gateway;

import io.fabric8.gateway.handlers.http.HttpGatewayServer;
import io.fabric8.gateway.handlers.http.MappedServices;
import org.junit.After;
import org.junit.Before;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.VertxFactory;
import org.vertx.java.core.http.HttpServer;

import java.util.HashMap;

public abstract class AbstractHttpGatewayTest {
    final HashMap<String, MappedServices> mappedServices = new HashMap<String, MappedServices>();
    // Setup Vertx
    protected Vertx vertx;
    HttpServer restEndpointServer;
    HttpGatewayServer httpGatewayServer;

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

    public abstract HttpServer startRestEndpoint() throws InterruptedException;

    @After
    public void endRestEndpoint() throws InterruptedException {
        if( restEndpointServer !=null ) {
            restEndpointServer.close();
            restEndpointServer = null;
        }
    }

    public abstract HttpGatewayServer startHttpGateway();

    @After
    public void stopHttpGateway(){
        if( httpGatewayServer!=null ) {
            httpGatewayServer.destroy();
            httpGatewayServer = null;
        }
    }
}
