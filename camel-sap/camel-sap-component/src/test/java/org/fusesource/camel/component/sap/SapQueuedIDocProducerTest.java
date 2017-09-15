package org.fusesource.camel.component.sap;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.fusesource.camel.component.sap.model.idoc.Document;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.api.mockito.mockpolicies.Slf4jMockPolicy;
import org.powermock.core.classloader.annotations.MockPolicy;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.sap.conn.idoc.IDocFactory;
import com.sap.conn.idoc.jco.JCoIDoc;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.ext.Environment;

import static org.fusesource.camel.component.sap.util.IDocUtil.DATE_FORMATTER;
import static org.fusesource.camel.component.sap.util.IDocUtil.TIME_FORMATTER;
import static org.fusesource.camel.component.sap.util.IDocUtil.bytesToHex;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Properties;

@RunWith(PowerMockRunner.class)
@MockPolicy({Slf4jMockPolicy.class})
@PrepareForTest({ JCoDestinationManager.class, Environment.class, JCoIDoc.class })
public class SapQueuedIDocProducerTest extends SapIDocTestSupport {

	@Override
	public void doPreSetup() throws Exception {
		super.doPreSetup();

		PowerMockito.mockStatic(JCoDestinationManager.class, JCoIDoc.class);
		when(JCoDestinationManager.getDestination(TEST_DEST)).thenReturn(mockDestination);
		when(JCoIDoc.getIDocRepository(mockDestination)).thenReturn(mockIDocRepository);
		when(JCoIDoc.getIDocFactory()).thenReturn(mockIDocFactory);
		
	}
	
	@Test
	public void testProducer() throws Exception{ 
		
		//
		// Given
		//
		
		Document document = createAndPopulateDocument();

		//
		// When
		//

		template.sendBody("direct:start", document);
	
		//
		// Then
		//
		
		verify(mockIDocDocument, times(1)).setArchiveKey(ARCHIVE_KEY_VALUE);
		verify(mockIDocDocument, times(1)).setClient(CLIENT_VALUE);
		verify(mockIDocDocument, times(1)).setCreationDate(DATE_VALUE.getTime());
		verify(mockIDocDocument, times(1)).setCreationTime(TIME_VALUE.getTime());
		verify(mockIDocDocument, times(1)).setDirection(DIRECTION_VALUE);
		verify(mockIDocDocument, times(1)).setEDIMessage(EDI_MESSAGE_VALUE);
		verify(mockIDocDocument, times(1)).setEDIMessageGroup(EDI_MESSAGE_GROUP_VALUE);
		verify(mockIDocDocument, times(1)).setEDIMessageType(EDI_MESSAGE_TYPE_VALUE);
		verify(mockIDocDocument, times(1)).setEDIStandardFlag(EDI_STANDARD_FLAG_VALUE);
		verify(mockIDocDocument, times(1)).setEDIStandardVersion(EDI_STANDARD_VERSION_VALUE);
		verify(mockIDocDocument, times(1)).setEDITransmissionFile(EDI_TRANSMISSION_FILE_VALUE);
		verify(mockIDocDocument, times(1)).setIDocCompoundType(IDOC_COMPOUND_TYPE_VALUE);
		verify(mockIDocDocument, times(1)).setIDocNumber(IDOC_NUMBER_VALUE);
		verify(mockIDocDocument, times(1)).setIDocSAPRelease(IDOC_SAP_RELEASE_VALUE);
		verify(mockIDocDocument, times(1)).setIDocType(IDOC_TYPE_VALUE);
		verify(mockIDocDocument, times(1)).setIDocTypeExtension(IDOC_TYPE_EXTENSION_VALUE);
		verify(mockIDocDocument, times(1)).setMessageCode(MESSAGE_CODE_VALUE);
		verify(mockIDocDocument, times(1)).setMessageFunction(MESSAGE_FUNCTION_VALUE);
		verify(mockIDocDocument, times(1)).setMessageType(MESSAGE_TYPE_VALUE);
		verify(mockIDocDocument, times(1)).setOutputMode(OUTPUT_MODE_VALUE);
		verify(mockIDocDocument, times(1)).setRecipientAddress(RECIPIENT_ADDRESS_VALUE);
		verify(mockIDocDocument, times(1)).setRecipientLogicalAddress(RECIPIENT_LOGICAL_ADDRESS_VALUE);
		verify(mockIDocDocument, times(1)).setRecipientPartnerFunction(RECIPIENT_PARTNER_FUNCTION_VALUE);
		verify(mockIDocDocument, times(1)).setRecipientPartnerNumber(RECIPIENT_PARTNER_NUMBER_VALUE);
		verify(mockIDocDocument, times(1)).setRecipientPartnerType(RECIPIENT_PARTNER_TYPE_VALUE);
		verify(mockIDocDocument, times(1)).setRecipientPort(RECIPIENT_PORT_VALUE);
		verify(mockIDocDocument, times(1)).setSenderAddress(SENDER_ADDRESS_VALUE);
		verify(mockIDocDocument, times(1)).setSenderLogicalAddress(SENDER_LOGICAL_ADDRESS_VALUE);
		verify(mockIDocDocument, times(1)).setSenderPartnerFunction(SENDER_PARTNER_FUNCTION_VALUE);
		verify(mockIDocDocument, times(1)).setSenderPartnerNumber(SENDER_PARTNER_NUMBER_VALUE);
		verify(mockIDocDocument, times(1)).setSenderPartnerType(SENDER_PARTNER_TYPE_VALUE);
		verify(mockIDocDocument, times(1)).setSenderPort(SENDER_PORT_VALUE);
		verify(mockIDocDocument, times(1)).setSerialization(SERIALIZATION_VALUE);
		verify(mockIDocDocument, times(1)).setStatus(STATUS_VALUE);
		verify(mockIDocDocument, times(1)).setTestFlag(TEST_FLAG_VALUE);
		
		verify(mockRootSegment, times(0)).setValue(anyString(), (byte[])anyObject());
		
		verify(mockLevel1Segment, times(1)).setValue(CHAR_FIELD, (String) CHAR_FIELD_VALUE);
		verify(mockLevel1Segment, times(1)).setValue(QUAN_FIELD, (String) QUAN_FIELD_VALUE.toString());
		verify(mockLevel1Segment, times(1)).setValue(UNIT_FIELD, (String) UNIT_FIELD_VALUE);
		verify(mockLevel1Segment, times(1)).setValue(NUMC_FIELD, (String) NUMC_FIELD_VALUE);
		verify(mockLevel1Segment, times(1)).setValue(DATS_FIELD, (String) DATE_FORMATTER.format(DATS_FIELD_VALUE));
		verify(mockLevel1Segment, times(1)).setValue(TIMS_FIELD, (String) TIME_FORMATTER.format(TIMS_FIELD_VALUE));
		verify(mockLevel1Segment, times(1)).setValue(CURR_FIELD, (String) CURR_FIELD_VALUE.toString());
		verify(mockLevel1Segment, times(1)).setValue(CUKY_FIELD, (String) CUKY_FIELD_VALUE);
		verify(mockLevel1Segment, times(1)).setValue(LANG_FIELD, (String) LANG_FIELD_VALUE);
		verify(mockLevel1Segment, times(1)).setValue(CLNT_FIELD, (String) CLNT_FIELD_VALUE);
		verify(mockLevel1Segment, times(1)).setValue(INT1_FIELD, (String) INT1_FIELD_VALUE.toString());
		verify(mockLevel1Segment, times(1)).setValue(INT2_FIELD, (String) INT2_FIELD_VALUE.toString());
		verify(mockLevel1Segment, times(1)).setValue(INT4_FIELD, (String) INT4_FIELD_VALUE.toString());
		verify(mockLevel1Segment, times(1)).setValue(FLTP_FIELD, (String) FLTP_FIELD_VALUE.toString());
		verify(mockLevel1Segment, times(1)).setValue(ACCP_FIELD, (String) ACCP_FIELD_VALUE);
		verify(mockLevel1Segment, times(1)).setValue(PREC_FIELD, (String) PREC_FIELD_VALUE);
		verify(mockLevel1Segment, times(1)).setValue(LRAW_FIELD, bytesToHex(LRAW_FIELD_VALUE));
		verify(mockLevel1Segment, times(1)).setValue(DEC_FIELD, (String) DEC_FIELD_VALUE.toString());
		verify(mockLevel1Segment, times(1)).setValue(RAW_FIELD, bytesToHex(RAW_FIELD_VALUE));
		verify(mockLevel1Segment, times(1)).setValue(STRING_FIELD, (String) STRING_FIELD_VALUE);
		verify(mockLevel1Segment, times(1)).setValue(RAWSTRING_FIELD, bytesToHex(RAWSTRING_FIELD_VALUE));
		
		verify(mockLevel2Segment, times(1)).setValue(CHAR_FIELD, (String) CHAR_FIELD_VALUE);
		verify(mockLevel2Segment, times(1)).setValue(QUAN_FIELD, (String) QUAN_FIELD_VALUE.toString());
		verify(mockLevel2Segment, times(1)).setValue(UNIT_FIELD, (String) UNIT_FIELD_VALUE);
		verify(mockLevel2Segment, times(1)).setValue(NUMC_FIELD, (String) NUMC_FIELD_VALUE);
		verify(mockLevel2Segment, times(1)).setValue(DATS_FIELD, (String) DATE_FORMATTER.format(DATS_FIELD_VALUE));
		verify(mockLevel2Segment, times(1)).setValue(TIMS_FIELD, (String) TIME_FORMATTER.format(TIMS_FIELD_VALUE));
		verify(mockLevel2Segment, times(1)).setValue(CURR_FIELD, (String) CURR_FIELD_VALUE.toString());
		verify(mockLevel2Segment, times(1)).setValue(CUKY_FIELD, (String) CUKY_FIELD_VALUE);
		verify(mockLevel2Segment, times(1)).setValue(LANG_FIELD, (String) LANG_FIELD_VALUE);
		verify(mockLevel2Segment, times(1)).setValue(CLNT_FIELD, (String) CLNT_FIELD_VALUE);
		verify(mockLevel2Segment, times(1)).setValue(INT1_FIELD, (String) INT1_FIELD_VALUE.toString());
		verify(mockLevel2Segment, times(1)).setValue(INT2_FIELD, (String) INT2_FIELD_VALUE.toString());
		verify(mockLevel2Segment, times(1)).setValue(INT4_FIELD, (String) INT4_FIELD_VALUE.toString());
		verify(mockLevel2Segment, times(1)).setValue(FLTP_FIELD, (String) FLTP_FIELD_VALUE.toString());
		verify(mockLevel2Segment, times(1)).setValue(ACCP_FIELD, (String) ACCP_FIELD_VALUE);
		verify(mockLevel2Segment, times(1)).setValue(PREC_FIELD, (String) PREC_FIELD_VALUE);
		verify(mockLevel2Segment, times(1)).setValue(LRAW_FIELD, bytesToHex(LRAW_FIELD_VALUE));
		verify(mockLevel2Segment, times(1)).setValue(DEC_FIELD, (String) DEC_FIELD_VALUE.toString());
		verify(mockLevel2Segment, times(1)).setValue(RAW_FIELD, bytesToHex(RAW_FIELD_VALUE));
		verify(mockLevel2Segment, times(1)).setValue(STRING_FIELD, (String) STRING_FIELD_VALUE);
		verify(mockLevel2Segment, times(1)).setValue(RAWSTRING_FIELD, bytesToHex(RAWSTRING_FIELD_VALUE));
		
		verify(mockLevel3Segment, times(1)).setValue(CHAR_FIELD, (String) CHAR_FIELD_VALUE);
		verify(mockLevel3Segment, times(1)).setValue(QUAN_FIELD, (String) QUAN_FIELD_VALUE.toString());
		verify(mockLevel3Segment, times(1)).setValue(UNIT_FIELD, (String) UNIT_FIELD_VALUE);
		verify(mockLevel3Segment, times(1)).setValue(NUMC_FIELD, (String) NUMC_FIELD_VALUE);
		verify(mockLevel3Segment, times(1)).setValue(DATS_FIELD, (String) DATE_FORMATTER.format(DATS_FIELD_VALUE));
		verify(mockLevel3Segment, times(1)).setValue(TIMS_FIELD, (String) TIME_FORMATTER.format(TIMS_FIELD_VALUE));
		verify(mockLevel3Segment, times(1)).setValue(CURR_FIELD, (String) CURR_FIELD_VALUE.toString());
		verify(mockLevel3Segment, times(1)).setValue(CUKY_FIELD, (String) CUKY_FIELD_VALUE);
		verify(mockLevel3Segment, times(1)).setValue(LANG_FIELD, (String) LANG_FIELD_VALUE);
		verify(mockLevel3Segment, times(1)).setValue(CLNT_FIELD, (String) CLNT_FIELD_VALUE);
		verify(mockLevel3Segment, times(1)).setValue(INT1_FIELD, (String) INT1_FIELD_VALUE.toString());
		verify(mockLevel3Segment, times(1)).setValue(INT2_FIELD, (String) INT2_FIELD_VALUE.toString());
		verify(mockLevel3Segment, times(1)).setValue(INT4_FIELD, (String) INT4_FIELD_VALUE.toString());
		verify(mockLevel3Segment, times(1)).setValue(FLTP_FIELD, (String) FLTP_FIELD_VALUE.toString());
		verify(mockLevel3Segment, times(1)).setValue(ACCP_FIELD, (String) ACCP_FIELD_VALUE);
		verify(mockLevel3Segment, times(1)).setValue(PREC_FIELD, (String) PREC_FIELD_VALUE);
		verify(mockLevel3Segment, times(1)).setValue(LRAW_FIELD, bytesToHex(LRAW_FIELD_VALUE));
		verify(mockLevel3Segment, times(1)).setValue(DEC_FIELD, (String) DEC_FIELD_VALUE.toString());
		verify(mockLevel3Segment, times(1)).setValue(RAW_FIELD, bytesToHex(RAW_FIELD_VALUE));
		verify(mockLevel3Segment, times(1)).setValue(STRING_FIELD, (String) STRING_FIELD_VALUE);
		verify(mockLevel3Segment, times(1)).setValue(RAWSTRING_FIELD, bytesToHex(RAWSTRING_FIELD_VALUE));
		
		PowerMockito.verifyStatic();
		JCoIDoc.send(mockIDocDocument, IDocFactory.IDOC_VERSION_DEFAULT, mockDestination, TEST_TID, TEST_QUEUE);

		Exchange exchange = getMockEndpoint("mock:result").getExchanges().get(0);

		// Check exchange properties
		@SuppressWarnings("unchecked")
		Map<String,Properties> destinationMap = exchange.getProperty(SapConstants.SAP_DESTINATION_PROPERTIES_MAP_EXCHANGE_PROPERTY, Map.class);
		assertNotNull("Exchange property '" + SapConstants.SAP_DESTINATION_PROPERTIES_MAP_EXCHANGE_PROPERTY + "' missing", destinationMap);
		Properties destinationProperties = destinationMap.get(TEST_DEST);
		assertNotNull("Destination properties for destination '" + TEST_DEST + "' missing", destinationProperties);

		// Check response headers
		assertThat("Message header '" + SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER + "' returned unexpected value", exchange.getIn().getHeader(SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER, String.class), is(SapConstants.SAP_QUEUED_IDOC_DESTINATION));
		assertThat("Message header '" + SapConstants.SAP_DESTINATION_NAME_MESSAGE_HEADER + "' returned unexpected value", exchange.getIn().getHeader(SapConstants.SAP_DESTINATION_NAME_MESSAGE_HEADER, String.class), is(TEST_DEST));
		assertThat("Message header '" + SapConstants.SAP_IDOC_TYPE_NAME_MESSAGE_HEADER + "' returned unexpected value", exchange.getIn().getHeader(SapConstants.SAP_IDOC_TYPE_NAME_MESSAGE_HEADER, String.class), is(TEST_IDOC_TYPE));
		assertThat("Message header '" + SapConstants.SAP_IDOC_TYPE_EXTENSION_NAME_MESSAGE_HEADER + "' returned unexpected value", exchange.getIn().getHeader(SapConstants.SAP_IDOC_TYPE_EXTENSION_NAME_MESSAGE_HEADER, String.class), is(TEST_IDOC_TYPE_EXTENSION));
		assertThat("Message header '" + SapConstants.SAP_SYSTEM_RELEASE_NAME_MESSAGE_HEADER + "' returned unexpected value", exchange.getIn().getHeader(SapConstants.SAP_SYSTEM_RELEASE_NAME_MESSAGE_HEADER, String.class), is(TEST_SYSTEM_RELEASE));
		assertThat("Message header '" + SapConstants.SAP_APPLICATION_RELEASE_NAME_MESSAGE_HEADER + "' returned unexpected value", exchange.getIn().getHeader(SapConstants.SAP_APPLICATION_RELEASE_NAME_MESSAGE_HEADER, String.class), is(TEST_APPLICATION_RELEASE));
	}

	@Override
	protected RouteBuilder createRouteBuilder() throws Exception {
		return new RouteBuilder() {
			@Override
			public void configure() throws Exception {
				from("direct:start").to("sap-qidoc-destination:TEST_DEST:TEST_QUEUE:TEST_IDOC_TYPE:TEST_IDOC_TYPE_EXTENSION:TEST_SYSTEM_VERSION:TEST_APPLICATION_VERSION").to("mock:result");
			}
		};
	}
}
