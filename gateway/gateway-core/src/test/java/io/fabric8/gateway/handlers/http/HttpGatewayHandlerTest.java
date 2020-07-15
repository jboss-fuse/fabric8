package io.fabric8.gateway.handlers.http;

import org.junit.Test;
import static io.fabric8.gateway.handlers.http.HttpGatewayHandler.normalizeUri;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    
    @Test
    public void testKnownFileExtension() {
        HttpGatewayHandler handler = new HttpGatewayHandler(null, null);
        assertTrue(handler.endWithKnowFileExtension("/cxf/CxfRsRouterTest/soap/test.wsdl/"));
        assertTrue(handler.endWithKnowFileExtension("/cxf/CxfRsRouterTest/rest/test.jsp/"));
        assertFalse(handler.endWithKnowFileExtension("/cxf/test/EUR/"));
        assertTrue(handler.endWithKnowFileExtension("/cxf/CxfRsRouterTest/rest/.pdf/"));
    }
}
