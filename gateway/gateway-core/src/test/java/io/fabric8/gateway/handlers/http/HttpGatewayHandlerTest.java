package io.fabric8.gateway.handlers.http;

import org.junit.Test;
import static io.fabric8.gateway.handlers.http.HttpGatewayHandler.normalizeUri;
import static org.junit.Assert.assertEquals;

/**
 * Test cases for {@link HttpGatewayHandler}
 */
public class HttpGatewayHandlerTest {

    @Test
    public void testNormalizeUri() {
        assertEquals(null, normalizeUri("/git/fabric/"));
        assertEquals("/git/fabric/", normalizeUri("/git/fabric"));

        assertEquals("/cxf/CxfRsRouterTest/rest/?id=123", normalizeUri("/cxf/CxfRsRouterTest/rest?id=123"));
    }
}
