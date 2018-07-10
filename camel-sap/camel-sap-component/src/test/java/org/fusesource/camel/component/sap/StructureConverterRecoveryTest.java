package org.fusesource.camel.component.sap;

import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.builder.RouteBuilder;
import org.fusesource.camel.component.sap.converter.StructureConverter;
import org.fusesource.camel.component.sap.model.rfc.Structure;
import org.fusesource.camel.component.sap.util.Util;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.api.mockito.mockpolicies.Slf4jMockPolicy;
import org.powermock.core.classloader.annotations.MockPolicy;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.sap.conn.idoc.jco.JCoIDoc;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.ext.Environment;

@RunWith(PowerMockRunner.class)
@MockPolicy({Slf4jMockPolicy.class})
@PrepareForTest({ JCoDestinationManager.class, Environment.class, JCoIDoc.class })
public class StructureConverterRecoveryTest extends SapRfcTestSupport {

	public static final String REQUEST_STRING = 
			"<?xml version=\"1.0\" encoding=\"ASCII\"?>" +
			"<TEST_FUNCTION_MODULE:Request xmlns:TEST_FUNCTION_MODULE=\"http://sap.fusesource.org/rfc/TEST_REPOSITORY/TEST_FUNCTION_MODULE\" PARAM_LIST_CHAR_PARAM=\"ABCDEFGHIJ\" PARAM_LIST_NUM_PARAM=\"0123456789\" PARAM_LIST_INT_PARAM=\"1968526677\" PARAM_LIST_FLOAT_PARAM=\"1.0E38\" PARAM_LIST_BCD_PARAM=\"100.00000000000001\" PARAM_LIST_BINARY_PARAM=\"55\" PARAM_LIST_BINARY_ARRAY_PARAM=\"FF0F1E2D3C4B5A607988\" PARAM_LIST_DATE_PARAM=\"" + DATE_PARAM_IN_VAL_STR + "\" PARAM_LIST_TIME_PARAM=\"" + TIME_PARAM_IN_VAL_STR + "\" PARAM_LIST_STRING_PARAM=\"Four score and seven years ago ...\">" +
			  "<PARAM_LIST_STRUCTURE_PARAM CHAR_PARAM=\"ABCDEFGHIJ\" NUM_PARAM=\"0123456789\" INT_PARAM=\"1968526677\" FLOAT_PARAM=\"1.0E38\" BCD_PARAM=\"100.00000000000001\" BINARY_PARAM=\"55\" BINARY_ARRAY_PARAM=\"FF0F1E2D3C4B5A607988\" DATE_PARAM=\"" + DATE_PARAM_IN_VAL_STR + "\" TIME_PARAM=\"" + TIME_PARAM_IN_VAL_STR + "\" STRING_PARAM=\"Four score and seven years ago ...\"/>" +
			  "<PARAM_LIST_TABLE_PARAM>" +
			    "<row CHAR_PARAM=\"ABCDEFGHIJ\" NUM_PARAM=\"0123456789\" INT_PARAM=\"1968526677\" FLOAT_PARAM=\"1.0E38\" BCD_PARAM=\"100.00000000000001\" BINARY_PARAM=\"55\" BINARY_ARRAY_PARAM=\"FF0F1E2D3C4B5A607988\" DATE_PARAM=\"" + DATE_PARAM_IN_VAL_STR + "\" TIME_PARAM=\"" + TIME_PARAM_IN_VAL_STR + "\" STRING_PARAM=\"Four score and seven years ago ...\"/>" +
			  "</PARAM_LIST_TABLE_PARAM>" +
			"</TEST_FUNCTION_MODULE:Request>";

	@Override
	public void doPreSetup() throws Exception {
		super.doPreSetup();

		PowerMockito.mockStatic(JCoDestinationManager.class, JCoIDoc.class);
		when(JCoDestinationManager.getDestination(DESTINATION_NAME)).thenReturn(mockDestination);
		when(JCoIDoc.getServer(SERVER_NAME)).thenReturn(mockServer);
	}

	@Test
	public void testToStructureFromStringWithBadInput() throws Exception {

		//
		// Given
		//
		
		File file = new File("data/testRfcRegistry.ecore");
		Util.loadRegistry(file);
		String badRequest = REQUEST_STRING.replace("CHAR_PARAM=\"ABCDEFGHIJ\"", "CHAR_PARAM=\"&ABCDEFGHIJ\"");
		String goodRequest = REQUEST_STRING;
		
		//
		// When
		//
		
		template.sendBody("direct:request", badRequest);
		template.sendBody("direct:request", goodRequest);

		//
		// Then
		//
		List<Exchange> exchanges = getMockEndpoint("mock:result").getExchanges();
		
		// First request string sent is invalid and should return a null request 
		Exchange exchange1 = exchanges.get(0);
		Message message1 = exchange1.getIn();
		Structure request1 = message1.getBody(Structure.class);
		assertNull("Invalid request string inadvertantly converted", request1);
		
		// Second request string sent is valid and should return a non-null request 
		Exchange exchange2 = exchanges.get(1);
		Message message2 = exchange2.getIn();
		Structure request2 = message2.getBody(Structure.class);
		assertNotNull("Subsequent valid request string not converted", request2);
		

	}

	@Override
	protected RouteBuilder createRouteBuilder() throws Exception {
		return new RouteBuilder() {
			@Override
			public void configure() throws Exception {
				from("direct:request").to("sap-srfc-destination:TEST_DEST:TEST_FUNCTION_MODULE").to("mock:result");
			}
		};
	}

}
