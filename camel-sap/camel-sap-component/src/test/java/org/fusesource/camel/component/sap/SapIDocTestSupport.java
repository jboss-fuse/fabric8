/**
 * Copyright 2014 Red Hat, Inc.
 * 
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 * 
 */
package org.fusesource.camel.component.sap;

import static org.fusesource.camel.component.sap.util.IDocUtil.DATE_FORMATTER;
import static org.fusesource.camel.component.sap.util.IDocUtil.TIME_FORMATTER;
import static org.fusesource.camel.component.sap.util.IDocUtil.bytesToHex;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.NoSuchElementException;

import org.fusesource.camel.component.sap.model.idoc.Document;
import org.fusesource.camel.component.sap.model.idoc.DocumentList;
import org.fusesource.camel.component.sap.model.idoc.IdocPackage;
import org.fusesource.camel.component.sap.model.idoc.Segment;
import org.fusesource.camel.component.sap.util.IDocUtil;
import org.junit.BeforeClass;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.StaticApplicationContext;

import com.sap.conn.idoc.IDocDatatype;
import com.sap.conn.idoc.IDocDocument;
import com.sap.conn.idoc.IDocDocumentIterator;
import com.sap.conn.idoc.IDocDocumentList;
import com.sap.conn.idoc.IDocFactory;
import com.sap.conn.idoc.IDocRecordMetaData;
import com.sap.conn.idoc.IDocRepository;
import com.sap.conn.idoc.IDocSegment;
import com.sap.conn.idoc.IDocSegmentMetaData;
import com.sap.conn.idoc.jco.JCoIDocServer;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.server.JCoServerContext;
import com.sap.conn.jco.server.JCoServerState;

public abstract class SapIDocTestSupport extends JCoTestSupport {
	
	public static final String TEST_PROGRAM_ID = "TEST_PROGRAM_ID";
	public static final String TEST_DEST = "TEST_DEST";
	public static final String TEST_SERVER = "TEST_SERVER";
	public static final String TEST_QUEUE = "TEST_QUEUE";
	
	public static final String CHAR_FIELD = "CHAR_FIELD";
	public static final String QUAN_FIELD = "QUAN_FIELD";
	public static final String UNIT_FIELD = "UNIT_FIELD";
	public static final String NUMC_FIELD = "NUMC_FIELD";
	public static final String DATS_FIELD = "DATS_FIELD";
	public static final String TIMS_FIELD = "TIMS_FIELD";
	public static final String CURR_FIELD = "CURR_FIELD";
	public static final String CUKY_FIELD = "CUKY_FIELD";
	public static final String LANG_FIELD = "LANG_FIELD";
	public static final String CLNT_FIELD = "CLNT_FIELD";
	public static final String INT1_FIELD = "INT1_FIELD";
	public static final String INT2_FIELD = "INT2_FIELD";
	public static final String INT4_FIELD = "INT4_FIELD";
	public static final String FLTP_FIELD = "FLTP_FIELD";
	public static final String ACCP_FIELD = "ACCP_FIELD";
	public static final String PREC_FIELD = "PREC_FIELD";
	public static final String LRAW_FIELD = "LRAW_FIELD";
	public static final String DEC_FIELD = "DEC_FIELD";
	public static final String RAW_FIELD = "RAW_FIELD";
	public static final String STRING_FIELD = "STRING_FIELD";
	public static final String RAWSTRING_FIELD = "RAWSTRING_FIELD";
	
	public static final String CHAR_FIELD_VALUE = "1234ABCDEF";
	public static final BigDecimal QUAN_FIELD_VALUE = new BigDecimal(1234567890123456789L);
	public static final String UNIT_FIELD_VALUE = "LBS";
	public static final String NUMC_FIELD_VALUE = "1234567890";
	public static final Date DATS_FIELD_VALUE = new GregorianCalendar(1863,06,03).getTime();
	public static final Date TIMS_FIELD_VALUE = new GregorianCalendar(1970,0,1,12,15,30).getTime();
	public static final BigDecimal CURR_FIELD_VALUE = new BigDecimal(1234567890123456789L);
	public static final String CUKY_FIELD_VALUE = "USD";
	public static final String LANG_FIELD_VALUE = "EN";
	public static final String CLNT_FIELD_VALUE = "100";
	public static final BigInteger INT1_FIELD_VALUE = BigInteger.valueOf(255);
	public static final BigInteger INT2_FIELD_VALUE = BigInteger.valueOf(65535);
	public static final BigInteger INT4_FIELD_VALUE = BigInteger.valueOf(4294967295L);
	public static final BigDecimal FLTP_FIELD_VALUE = new BigDecimal(2.5e14);
	public static final String ACCP_FIELD_VALUE = "186307";
	public static final String PREC_FIELD_VALUE = "12";
	public static final byte[] LRAW_FIELD_VALUE = new byte[] {0x0F, 0x0E, 0x0D, 0x0C, 0x0B, 0x0A, 0x09, 0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01, 0x00 };
	public static final BigDecimal DEC_FIELD_VALUE =  new BigDecimal(1234567890L);
	public static final byte[] RAW_FIELD_VALUE = new byte[] {0x0F, 0x0E, 0x0D, 0x0C, 0x0B, 0x0A, 0x09, 0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01, 0x00 };
	public static final String STRING_FIELD_VALUE = "01234567890ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	public static final byte[] RAWSTRING_FIELD_VALUE = new byte[] {0x0F, 0x0E, 0x0D, 0x0C, 0x0B, 0x0A, 0x09, 0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01, 0x00 };
	
	public static final String[] RECORD_FIELD_VALUES = new String[] { "A", "B", "C" };
	public static final String[] RECORD_FIELD_VALUE_DESCRIPTIONS = new String[] { "A Description", "B Description", "C Description" };
	public static final String[][] RECORD_FIELD_VALUE_RANGES = new String[][] { { "A", null }, { "B", null }, { "C", null } };
	
	public static final String TEST_TID = "TEST_TID";
	public static final String TEST_REPOSITORY = "TEST_REPOSITORY";
	public static final String ROOT_DESCRIPTION = "ROOT_DESCRIPTION";
	public static final String LEVEL1_DESCRIPTION = "LEVEL1_DESCRIPTION";
	public static final String LEVEL2_DESCRIPTION = "LEVEL2_DESCRIPTION";
	public static final String LEVEL3_DESCRIPTION = "LEVEL3_DESCRIPTION";
	public static final String LEVEL1 = "LEVEL1";
	public static final String LEVEL2 = "LEVEL2";
	public static final String LEVEL3 = "LEVEL3";
	public static final String ROOT = "ROOT";
	public static final String BAR = "|";
	public static final String TEST_APPLICATION_RELEASE = "TEST_APPLICATION_VERSION";
	public static final String TEST_SYSTEM_RELEASE = "TEST_SYSTEM_VERSION";
	public static final String TEST_IDOC_TYPE_EXTENSION = "TEST_IDOC_TYPE_EXTENSION";
	public static final String TEST_IDOC_TYPE = "TEST_IDOC_TYPE";
	public static final String TEST_URL = IdocPackage.eNS_URI + "/" + TEST_REPOSITORY + "/" + TEST_IDOC_TYPE + "/" + TEST_IDOC_TYPE_EXTENSION + "/" + TEST_SYSTEM_RELEASE + "/" + TEST_APPLICATION_RELEASE;
	
	
	public static final String ROOT_SEGMENT_KEY = TEST_IDOC_TYPE + BAR + TEST_IDOC_TYPE_EXTENSION  + BAR + TEST_SYSTEM_RELEASE + BAR + TEST_APPLICATION_RELEASE + BAR + ROOT;
	public static final String LEVEL1_SEGMENT_KEY = TEST_IDOC_TYPE + BAR + TEST_IDOC_TYPE_EXTENSION  + BAR + TEST_SYSTEM_RELEASE + BAR + TEST_APPLICATION_RELEASE + BAR + LEVEL1;
	public static final String LEVEL2_SEGMENT_KEY = TEST_IDOC_TYPE + BAR + TEST_IDOC_TYPE_EXTENSION  + BAR + TEST_SYSTEM_RELEASE + BAR + TEST_APPLICATION_RELEASE + BAR + LEVEL2;
	public static final String LEVEL3_SEGMENT_KEY = TEST_IDOC_TYPE + BAR + TEST_IDOC_TYPE_EXTENSION  + BAR + TEST_SYSTEM_RELEASE + BAR + TEST_APPLICATION_RELEASE + BAR + LEVEL3;

	public static final String TEST_FLAG_VALUE = "testFlagValue";
	public static final String STATUS_VALUE = "statusValue";
	public static final String SERIALIZATION_VALUE = "serializationValue";
	public static final String SENDER_PORT_VALUE = "senderPortValue";
	public static final String SENDER_PARTNER_TYPE_VALUE = "senderPartnerTypeValue";
	public static final String SENDER_PARTNER_NUMBER_VALUE = "senderPartnerNumberValue";
	public static final String SENDER_PARTNER_FUNCTION_VALUE = "senderPartnerFunctionValue";
	public static final String SENDER_LOGICAL_ADDRESS_VALUE = "senderLogicalAddressValue";
	public static final String SENDER_ADDRESS_VALUE = "senderAddressValue";
	public static final String RECIPIENT_PORT_VALUE = "recipientPortValue";
	public static final String RECIPIENT_PARTNER_NUMBER_VALUE = "recipientPartnerNumberValue";
	public static final String RECIPIENT_PARTNER_TYPE_VALUE = "recipientPartnerTypeValue";
	public static final String RECIPIENT_PARTNER_FUNCTION_VALUE = "recipientPartnerFunctionValue";
	public static final String RECIPIENT_LOGICAL_ADDRESS_VALUE = "recipientLogicalAddressValue";
	public static final String RECIPIENT_ADDRESS_VALUE = "recipientAddressValue";
	public static final String OUTPUT_MODE_VALUE = "outputModeValue";
	public static final String MESSAGE_TYPE_VALUE = "messageTypeValue";
	public static final String MESSAGE_FUNCTION_VALUE = "messageFunctionValue";
	public static final String MESSAGE_CODE_VALUE = "messageCodeValue";
	public static final String IDOC_TYPE_EXTENSION_VALUE = "idocTypeExtensionValue";
	public static final String IDOC_TYPE_VALUE = "idocTypeValue";
	public static final String IDOC_SAP_RELEASE_VALUE = "idocSAPReleaseValue";
	public static final String IDOC_NUMBER_VALUE = "idocNumberValue";
	public static final String IDOC_COMPOUND_TYPE_VALUE = "idocCompoundTypeValue";
	public static final String EDI_TRANSMISSION_FILE_VALUE = "ediTransmissionFileValue";
	public static final String EDI_STANDARD_VERSION_VALUE = "ediStandardVersionValue";
	public static final String EDI_STANDARD_FLAG_VALUE = "ediStandardFlagValue";
	public static final String EDI_MESSAGE_TYPE_VALUE = "editMessageTypeValue";
	public static final String EDI_MESSAGE_GROUP_VALUE = "editMessageGroupValue";
	public static final String EDI_MESSAGE_VALUE = "ediMessageValue";
	public static final String DIRECTION_VALUE = "directionValue";
	public static final String CLIENT_VALUE = "clientValue";
	public static final String ARCHIVE_KEY_VALUE = "archiveKeyValue";
	
	protected static Calendar DATE_VALUE;
	protected static Calendar TIME_VALUE;

	protected JCoDestination mockDestination;
	protected JCoIDocServer mockIDocServer;
	protected JCoServerContext mockServerContext;
	protected IDocRepository mockIDocRepository;
	protected IDocFactory mockIDocFactory;
	protected IDocHandlerFactory mockIDocHandlerFactory;
	protected IDocDocumentList mockIDocDocumentList;
	protected IDocDocumentIterator mockIDocDocumentListIterator;
	protected IDocDocument mockIDocDocument;
	protected IDocSegment mockRootSegment;
	protected IDocSegment mockLevel1Segment;
	protected IDocSegment mockLevel2Segment;
	protected IDocSegment mockLevel3Segment;
	
	protected IDocSegmentMetaData mockRootSegmentMetaData;
	protected IDocSegmentMetaData mockLevel1SegmentMetaData;
	protected IDocSegmentMetaData mockLevel2SegmentMetaData;
	protected IDocSegmentMetaData mockLevel3SegmentMetaData;
	
	protected IDocRecordMetaData mockRootRecordMetaData;
	protected IDocRecordMetaData mockLevel1RecordMetaData;
	protected IDocRecordMetaData mockLevel2RecordMetaData;
	protected IDocRecordMetaData mockLevel3RecordMetaData;

	@BeforeClass
	public static void setupIDocTestSupportClass() {
		DATE_VALUE = Calendar.getInstance();
		DATE_VALUE.set(1861, Calendar.APRIL, 12);
		TIME_VALUE = Calendar.getInstance();
		TIME_VALUE.set(Calendar.HOUR, 4);
		TIME_VALUE.set(Calendar.MINUTE, 30);
		TIME_VALUE.set(Calendar.SECOND, 15);
	}
	
	public void createMocks() throws Exception {

		/* Create mocks for destination, IDoc Respository, IDoc Factory and IDoc Document  */
		mockDestination = mock(JCoDestination.class, "TestDestination");
		mockIDocServer = mock(JCoIDocServer.class);
		mockServerContext = mock(JCoServerContext.class);
		mockIDocRepository = mock(IDocRepository.class, "IDocRepository");
		mockIDocFactory = mock(IDocFactory.class, "IDocFactory");
		mockIDocDocumentList = mock(IDocDocumentList.class);
		mockIDocDocumentListIterator = mock(IDocDocumentIterator.class);
		mockIDocHandlerFactory = mock(IDocHandlerFactory.class);
		mockIDocDocument = mock(IDocDocument.class, "IDocDocument");
		
		/* Create mocks for segments */
		mockRootSegment = mock(IDocSegment.class, "RootSegment");
		mockLevel1Segment = mock(IDocSegment.class, "Level1Segment");
		mockLevel2Segment = mock(IDocSegment.class, "Level2Segment");
		mockLevel3Segment = mock(IDocSegment.class, "Level3Segment");
		
		/* Create mocks for segment meta data */
		mockRootSegmentMetaData = mock(IDocSegmentMetaData.class);
		mockLevel1SegmentMetaData = mock(IDocSegmentMetaData.class);
		mockLevel2SegmentMetaData = mock(IDocSegmentMetaData.class);
		mockLevel3SegmentMetaData = mock(IDocSegmentMetaData.class);
		
		/* Create mocks for record meta data */
		mockRootRecordMetaData = mock(IDocRecordMetaData.class);
		mockLevel1RecordMetaData = mock(IDocRecordMetaData.class);
		mockLevel2RecordMetaData = mock(IDocRecordMetaData.class);
		mockLevel3RecordMetaData = mock(IDocRecordMetaData.class);
		
	}
	
	public void enhanceRootSegment() throws Exception {

		/* Enhance Root Segment meta data mock */
		when(mockRootSegmentMetaData.getRecordMetaData()).thenReturn(mockRootRecordMetaData);
		when(mockRootSegmentMetaData.getType()).thenReturn(ROOT);
		when(mockRootSegmentMetaData.getKey()).thenReturn(ROOT_SEGMENT_KEY);
		when(mockRootSegmentMetaData.getDefinition()).thenReturn(ROOT);
		when(mockRootSegmentMetaData.getDescription()).thenReturn(ROOT_DESCRIPTION);
		when(mockRootSegmentMetaData.getHierarchyLevel()).thenReturn(0);
		when(mockRootSegmentMetaData.getIDocType()).thenReturn(TEST_IDOC_TYPE);
		when(mockRootSegmentMetaData.getIDocTypeExtension()).thenReturn(TEST_IDOC_TYPE_EXTENSION);
		when(mockRootSegmentMetaData.getSystemRelease()).thenReturn(TEST_SYSTEM_RELEASE);
		when(mockRootSegmentMetaData.getApplicationRelease()).thenReturn(TEST_APPLICATION_RELEASE);
		when(mockRootSegmentMetaData.getMaxOccurrence()).thenReturn(1L);
		when(mockRootSegmentMetaData.getMinOccurrence()).thenReturn(1L);
		when(mockRootSegmentMetaData.isLocked()).thenReturn(true);
		when(mockRootSegmentMetaData.isMandatory()).thenReturn(true);
		when(mockRootSegmentMetaData.isQualified()).thenReturn(false);
		when(mockRootSegmentMetaData.getChildren()).thenReturn(new IDocSegmentMetaData[] { mockLevel1SegmentMetaData });
		
		/* Enhance Root Segment record meta data mock */
		when(mockRootRecordMetaData.getName()).thenReturn(ROOT);
		when(mockRootRecordMetaData.getNumFields()).thenReturn(0);
		when(mockRootRecordMetaData.getRecordLength()).thenReturn(0);
		
		/* Enhance Root Segment mock */
		when(mockRootSegment.getSegmentMetaData()).thenReturn(mockRootSegmentMetaData);
		when(mockRootSegment.getRecordMetaData()).thenReturn(mockRootRecordMetaData);
		when(mockRootSegment.addChild(LEVEL1)).thenReturn(mockLevel1Segment);
		when(mockRootSegment.getChildren(LEVEL1)).thenReturn( new IDocSegment[] { mockLevel1Segment });

	}
	
	public void enhanceLevel1Segment() throws Exception {
		
		/* Enhance Level 1 Segment meta data mock */
		when(mockLevel1SegmentMetaData.getRecordMetaData()).thenReturn(mockLevel1RecordMetaData);
		when(mockLevel1SegmentMetaData.getType()).thenReturn(LEVEL1);
		when(mockLevel1SegmentMetaData.getKey()).thenReturn(LEVEL1_SEGMENT_KEY);
		when(mockLevel1SegmentMetaData.getDefinition()).thenReturn(LEVEL1);
		when(mockLevel1SegmentMetaData.getDescription()).thenReturn(LEVEL1_DESCRIPTION);
		when(mockLevel1SegmentMetaData.getHierarchyLevel()).thenReturn(1);
		when(mockLevel1SegmentMetaData.getIDocType()).thenReturn(TEST_IDOC_TYPE);
		when(mockLevel1SegmentMetaData.getIDocTypeExtension()).thenReturn(TEST_IDOC_TYPE_EXTENSION);
		when(mockLevel1SegmentMetaData.getSystemRelease()).thenReturn(TEST_SYSTEM_RELEASE);
		when(mockLevel1SegmentMetaData.getApplicationRelease()).thenReturn(TEST_APPLICATION_RELEASE);
		when(mockLevel1SegmentMetaData.getMaxOccurrence()).thenReturn(9999999999L);
		when(mockLevel1SegmentMetaData.getMinOccurrence()).thenReturn(1L);
		when(mockLevel1SegmentMetaData.isLocked()).thenReturn(true);
		when(mockLevel1SegmentMetaData.isMandatory()).thenReturn(true);
		when(mockLevel1SegmentMetaData.isQualified()).thenReturn(false);
		when(mockLevel1SegmentMetaData.getChildren()).thenReturn(new IDocSegmentMetaData[] { mockLevel2SegmentMetaData });
		
		/* Enhance Level  Segment record meta data mock */
		when(mockLevel1RecordMetaData.getName()).thenReturn(LEVEL1);
		when(mockLevel1RecordMetaData.getNumFields()).thenReturn(21);
		when(mockLevel1RecordMetaData.getRecordLength()).thenReturn(210);
		
		enhanceSegmentRecordMetaData(mockLevel1RecordMetaData);
		
		when(mockLevel1Segment.getRecordMetaData()).thenReturn(mockLevel1RecordMetaData);
		when(mockLevel1Segment.addChild(LEVEL2)).thenReturn(mockLevel2Segment);
		when(mockLevel1Segment.getChildren(LEVEL2)).thenReturn(new IDocSegment[] { mockLevel2Segment });
		when(mockLevel1Segment.getString(CHAR_FIELD)).thenReturn(CHAR_FIELD_VALUE);
		when(mockLevel1Segment.getString(QUAN_FIELD)).thenReturn(QUAN_FIELD_VALUE.toString());
		when(mockLevel1Segment.getString(UNIT_FIELD)).thenReturn(UNIT_FIELD_VALUE);
		when(mockLevel1Segment.getString(NUMC_FIELD)).thenReturn(NUMC_FIELD_VALUE);
		when(mockLevel1Segment.getString(DATS_FIELD)).thenReturn(DATE_FORMATTER.format(DATS_FIELD_VALUE));
		when(mockLevel1Segment.getString(TIMS_FIELD)).thenReturn(TIME_FORMATTER.format(TIMS_FIELD_VALUE));
		when(mockLevel1Segment.getString(CURR_FIELD)).thenReturn(CURR_FIELD_VALUE.toString());
		when(mockLevel1Segment.getString(CUKY_FIELD)).thenReturn(CUKY_FIELD_VALUE);
		when(mockLevel1Segment.getString(LANG_FIELD)).thenReturn(LANG_FIELD_VALUE);
		when(mockLevel1Segment.getString(CLNT_FIELD)).thenReturn(CLNT_FIELD_VALUE);
		when(mockLevel1Segment.getString(INT1_FIELD)).thenReturn(INT1_FIELD_VALUE.toString());
		when(mockLevel1Segment.getString(INT2_FIELD)).thenReturn(INT2_FIELD_VALUE.toString());
		when(mockLevel1Segment.getString(INT4_FIELD)).thenReturn(INT4_FIELD_VALUE.toString());
		when(mockLevel1Segment.getString(FLTP_FIELD)).thenReturn(FLTP_FIELD_VALUE.toString());
		when(mockLevel1Segment.getString(ACCP_FIELD)).thenReturn(ACCP_FIELD_VALUE);
		when(mockLevel1Segment.getString(PREC_FIELD)).thenReturn(PREC_FIELD_VALUE);
		when(mockLevel1Segment.getString(LRAW_FIELD)).thenReturn(bytesToHex(LRAW_FIELD_VALUE));
		when(mockLevel1Segment.getString(DEC_FIELD)).thenReturn(DEC_FIELD_VALUE.toString());
		when(mockLevel1Segment.getString(RAW_FIELD)).thenReturn(bytesToHex(RAW_FIELD_VALUE));
		when(mockLevel1Segment.getString(STRING_FIELD)).thenReturn(STRING_FIELD_VALUE);
		when(mockLevel1Segment.getString(RAWSTRING_FIELD)).thenReturn(bytesToHex(RAWSTRING_FIELD_VALUE));

	}
	
	public void enhanceLevel2Segment() throws Exception {
		
		/* Enhance Level 2 Segment meta data mock */
		when(mockLevel2SegmentMetaData.getRecordMetaData()).thenReturn(mockLevel2RecordMetaData);
		when(mockLevel2SegmentMetaData.getType()).thenReturn(LEVEL2);
		when(mockLevel2SegmentMetaData.getKey()).thenReturn(LEVEL2_SEGMENT_KEY);
		when(mockLevel2SegmentMetaData.getDefinition()).thenReturn(LEVEL2);
		when(mockLevel2SegmentMetaData.getDescription()).thenReturn(LEVEL2_DESCRIPTION);
		when(mockLevel2SegmentMetaData.getHierarchyLevel()).thenReturn(2);
		when(mockLevel2SegmentMetaData.getIDocType()).thenReturn(TEST_IDOC_TYPE);
		when(mockLevel2SegmentMetaData.getIDocTypeExtension()).thenReturn(TEST_IDOC_TYPE_EXTENSION);
		when(mockLevel2SegmentMetaData.getSystemRelease()).thenReturn(TEST_SYSTEM_RELEASE);
		when(mockLevel2SegmentMetaData.getApplicationRelease()).thenReturn(TEST_APPLICATION_RELEASE);
		when(mockLevel2SegmentMetaData.getMaxOccurrence()).thenReturn(9999999999L);
		when(mockLevel2SegmentMetaData.getMinOccurrence()).thenReturn(1L);
		when(mockLevel2SegmentMetaData.isLocked()).thenReturn(true);
		when(mockLevel2SegmentMetaData.isMandatory()).thenReturn(true);
		when(mockLevel2SegmentMetaData.isQualified()).thenReturn(false);
		when(mockLevel2SegmentMetaData.getChildren()).thenReturn(new IDocSegmentMetaData[] { mockLevel3SegmentMetaData });
		
		/* Enhance Level 2 Segment record meta data mock */
		when(mockLevel2RecordMetaData.getName()).thenReturn(LEVEL2);
		when(mockLevel2RecordMetaData.getNumFields()).thenReturn(21);
		when(mockLevel2RecordMetaData.getRecordLength()).thenReturn(210);
		
		enhanceSegmentRecordMetaData(mockLevel2RecordMetaData);
		
		/* Enhance Level 3 Segment meta data mock */
		when(mockLevel3SegmentMetaData.getRecordMetaData()).thenReturn(mockLevel3RecordMetaData);
		when(mockLevel3SegmentMetaData.getType()).thenReturn(LEVEL3);
		when(mockLevel3SegmentMetaData.getKey()).thenReturn(LEVEL3_SEGMENT_KEY);
		when(mockLevel3SegmentMetaData.getDefinition()).thenReturn(LEVEL3);
		when(mockLevel3SegmentMetaData.getDescription()).thenReturn(LEVEL3_DESCRIPTION);
		when(mockLevel3SegmentMetaData.getHierarchyLevel()).thenReturn(2);
		when(mockLevel3SegmentMetaData.getIDocType()).thenReturn(TEST_IDOC_TYPE);
		when(mockLevel3SegmentMetaData.getIDocTypeExtension()).thenReturn(TEST_IDOC_TYPE_EXTENSION);
		when(mockLevel3SegmentMetaData.getSystemRelease()).thenReturn(TEST_SYSTEM_RELEASE);
		when(mockLevel3SegmentMetaData.getApplicationRelease()).thenReturn(TEST_APPLICATION_RELEASE);
		when(mockLevel3SegmentMetaData.getMaxOccurrence()).thenReturn(9999999999L);
		when(mockLevel3SegmentMetaData.getMinOccurrence()).thenReturn(1L);
		when(mockLevel3SegmentMetaData.isLocked()).thenReturn(true);
		when(mockLevel3SegmentMetaData.isMandatory()).thenReturn(true);
		when(mockLevel3SegmentMetaData.isQualified()).thenReturn(false);
		when(mockLevel3SegmentMetaData.getChildren()).thenReturn(new IDocSegmentMetaData[0]);
		
		when(mockLevel2Segment.getRecordMetaData()).thenReturn(mockLevel2RecordMetaData);
		when(mockLevel2Segment.addChild(LEVEL3)).thenReturn(mockLevel3Segment);
		when(mockLevel2Segment.getChildren(LEVEL3)).thenReturn(new IDocSegment[] { mockLevel3Segment });
		when(mockLevel1Segment.getRecordMetaData()).thenReturn(mockLevel1RecordMetaData);
		when(mockLevel2Segment.addChild(LEVEL2)).thenReturn(mockLevel2Segment);
		when(mockLevel2Segment.getChildren(LEVEL2)).thenReturn(new IDocSegment[] { mockLevel2Segment });
		when(mockLevel2Segment.getString(CHAR_FIELD)).thenReturn(CHAR_FIELD_VALUE);
		when(mockLevel2Segment.getString(QUAN_FIELD)).thenReturn(QUAN_FIELD_VALUE.toString());
		when(mockLevel2Segment.getString(UNIT_FIELD)).thenReturn(UNIT_FIELD_VALUE);
		when(mockLevel2Segment.getString(NUMC_FIELD)).thenReturn(NUMC_FIELD_VALUE);
		when(mockLevel2Segment.getString(DATS_FIELD)).thenReturn(DATE_FORMATTER.format(DATS_FIELD_VALUE));
		when(mockLevel2Segment.getString(TIMS_FIELD)).thenReturn(TIME_FORMATTER.format(TIMS_FIELD_VALUE));
		when(mockLevel2Segment.getString(CURR_FIELD)).thenReturn(CURR_FIELD_VALUE.toString());
		when(mockLevel2Segment.getString(CUKY_FIELD)).thenReturn(CUKY_FIELD_VALUE);
		when(mockLevel2Segment.getString(LANG_FIELD)).thenReturn(LANG_FIELD_VALUE);
		when(mockLevel2Segment.getString(CLNT_FIELD)).thenReturn(CLNT_FIELD_VALUE);
		when(mockLevel2Segment.getString(INT1_FIELD)).thenReturn(INT1_FIELD_VALUE.toString());
		when(mockLevel2Segment.getString(INT2_FIELD)).thenReturn(INT2_FIELD_VALUE.toString());
		when(mockLevel2Segment.getString(INT4_FIELD)).thenReturn(INT4_FIELD_VALUE.toString());
		when(mockLevel2Segment.getString(FLTP_FIELD)).thenReturn(FLTP_FIELD_VALUE.toString());
		when(mockLevel2Segment.getString(ACCP_FIELD)).thenReturn(ACCP_FIELD_VALUE);
		when(mockLevel2Segment.getString(PREC_FIELD)).thenReturn(PREC_FIELD_VALUE);
		when(mockLevel2Segment.getString(LRAW_FIELD)).thenReturn(bytesToHex(LRAW_FIELD_VALUE));
		when(mockLevel2Segment.getString(DEC_FIELD)).thenReturn(DEC_FIELD_VALUE.toString());
		when(mockLevel2Segment.getString(RAW_FIELD)).thenReturn(bytesToHex(RAW_FIELD_VALUE));
		when(mockLevel2Segment.getString(STRING_FIELD)).thenReturn(STRING_FIELD_VALUE);
		when(mockLevel2Segment.getString(RAWSTRING_FIELD)).thenReturn(bytesToHex(RAWSTRING_FIELD_VALUE));
		
	}

	public void enhanceLevel3Segment() throws Exception {

		/* Enhance Level 3 Segment meta data mock */
		when(mockLevel3SegmentMetaData.getRecordMetaData()).thenReturn(mockLevel3RecordMetaData);
		when(mockLevel3SegmentMetaData.getType()).thenReturn(LEVEL3);
		when(mockLevel3SegmentMetaData.getKey()).thenReturn(LEVEL2_SEGMENT_KEY);
		when(mockLevel3SegmentMetaData.getDefinition()).thenReturn(LEVEL3);
		when(mockLevel3SegmentMetaData.getDescription()).thenReturn(LEVEL3_DESCRIPTION);
		when(mockLevel3SegmentMetaData.getHierarchyLevel()).thenReturn(3);
		when(mockLevel3SegmentMetaData.getIDocType()).thenReturn(TEST_IDOC_TYPE);
		when(mockLevel3SegmentMetaData.getIDocTypeExtension()).thenReturn(TEST_IDOC_TYPE_EXTENSION);
		when(mockLevel3SegmentMetaData.getSystemRelease()).thenReturn(TEST_SYSTEM_RELEASE);
		when(mockLevel3SegmentMetaData.getApplicationRelease()).thenReturn(TEST_APPLICATION_RELEASE);
		when(mockLevel3SegmentMetaData.getMaxOccurrence()).thenReturn(9999999999L);
		when(mockLevel3SegmentMetaData.getMinOccurrence()).thenReturn(1L);
		when(mockLevel3SegmentMetaData.isLocked()).thenReturn(true);
		when(mockLevel3SegmentMetaData.isMandatory()).thenReturn(true);
		when(mockLevel3SegmentMetaData.isQualified()).thenReturn(false);
		when(mockLevel3SegmentMetaData.getChildren()).thenReturn(new IDocSegmentMetaData[] { });
		
		/* Enhance Level 3 Segment record meta data mock */
		when(mockLevel3RecordMetaData.getName()).thenReturn(LEVEL3);
		when(mockLevel3RecordMetaData.getNumFields()).thenReturn(21);
		when(mockLevel3RecordMetaData.getRecordLength()).thenReturn(210);
		
		enhanceSegmentRecordMetaData(mockLevel3RecordMetaData);
		
		when(mockLevel3Segment.getRecordMetaData()).thenReturn(mockLevel2RecordMetaData);
		when(mockLevel3Segment.getString(CHAR_FIELD)).thenReturn(CHAR_FIELD_VALUE);
		when(mockLevel3Segment.getString(QUAN_FIELD)).thenReturn(QUAN_FIELD_VALUE.toString());
		when(mockLevel3Segment.getString(UNIT_FIELD)).thenReturn(UNIT_FIELD_VALUE);
		when(mockLevel3Segment.getString(NUMC_FIELD)).thenReturn(NUMC_FIELD_VALUE);
		when(mockLevel3Segment.getString(DATS_FIELD)).thenReturn(DATE_FORMATTER.format(DATS_FIELD_VALUE));
		when(mockLevel3Segment.getString(TIMS_FIELD)).thenReturn(TIME_FORMATTER.format(TIMS_FIELD_VALUE));
		when(mockLevel3Segment.getString(CURR_FIELD)).thenReturn(CURR_FIELD_VALUE.toString());
		when(mockLevel3Segment.getString(CUKY_FIELD)).thenReturn(CUKY_FIELD_VALUE);
		when(mockLevel3Segment.getString(LANG_FIELD)).thenReturn(LANG_FIELD_VALUE);
		when(mockLevel3Segment.getString(CLNT_FIELD)).thenReturn(CLNT_FIELD_VALUE);
		when(mockLevel3Segment.getString(INT1_FIELD)).thenReturn(INT1_FIELD_VALUE.toString());
		when(mockLevel3Segment.getString(INT2_FIELD)).thenReturn(INT2_FIELD_VALUE.toString());
		when(mockLevel3Segment.getString(INT4_FIELD)).thenReturn(INT4_FIELD_VALUE.toString());
		when(mockLevel3Segment.getString(FLTP_FIELD)).thenReturn(FLTP_FIELD_VALUE.toString());
		when(mockLevel3Segment.getString(ACCP_FIELD)).thenReturn(ACCP_FIELD_VALUE);
		when(mockLevel3Segment.getString(PREC_FIELD)).thenReturn(PREC_FIELD_VALUE);
		when(mockLevel3Segment.getString(LRAW_FIELD)).thenReturn(bytesToHex(LRAW_FIELD_VALUE));
		when(mockLevel3Segment.getString(DEC_FIELD)).thenReturn(DEC_FIELD_VALUE.toString());
		when(mockLevel3Segment.getString(RAW_FIELD)).thenReturn(bytesToHex(RAW_FIELD_VALUE));
		when(mockLevel3Segment.getString(STRING_FIELD)).thenReturn(STRING_FIELD_VALUE);
		when(mockLevel3Segment.getString(RAWSTRING_FIELD)).thenReturn(bytesToHex(RAWSTRING_FIELD_VALUE));
		
	}

	public void enhanceIDocDocument() throws Exception {

		/* Enhance IDoc Document mock */
		when(mockIDocDocument.getRootSegment()).thenReturn(mockRootSegment);
		when(mockIDocDocument.getArchiveKey()).thenReturn(ARCHIVE_KEY_VALUE);
		when(mockIDocDocument.getClient()).thenReturn(CLIENT_VALUE);
		when(mockIDocDocument.getCreationDate()).thenReturn(DATE_VALUE.getTime());
		when(mockIDocDocument.getCreationTime()).thenReturn(TIME_VALUE.getTime());
		when(mockIDocDocument.getDirection()).thenReturn(DIRECTION_VALUE);
		when(mockIDocDocument.getEDIMessage()).thenReturn(EDI_MESSAGE_VALUE);
		when(mockIDocDocument.getEDIMessageGroup()).thenReturn(EDI_MESSAGE_GROUP_VALUE);
		when(mockIDocDocument.getEDIMessageType()).thenReturn(EDI_MESSAGE_TYPE_VALUE);
		when(mockIDocDocument.getEDIStandardFlag()).thenReturn(EDI_STANDARD_FLAG_VALUE);
		when(mockIDocDocument.getEDIStandardVersion()).thenReturn(EDI_STANDARD_VERSION_VALUE);
		when(mockIDocDocument.getEDITransmissionFile()).thenReturn(EDI_TRANSMISSION_FILE_VALUE);
		when(mockIDocDocument.getIDocCompoundType()).thenReturn(IDOC_COMPOUND_TYPE_VALUE);
		when(mockIDocDocument.getIDocNumber()).thenReturn(IDOC_NUMBER_VALUE);
		when(mockIDocDocument.getIDocSAPRelease()).thenReturn(IDOC_SAP_RELEASE_VALUE);
		when(mockIDocDocument.getIDocType()).thenReturn(IDOC_TYPE_VALUE);
		when(mockIDocDocument.getIDocTypeExtension()).thenReturn(IDOC_TYPE_EXTENSION_VALUE);
		when(mockIDocDocument.getMessageCode()).thenReturn(MESSAGE_CODE_VALUE);
		when(mockIDocDocument.getMessageFunction()).thenReturn(MESSAGE_FUNCTION_VALUE);
		when(mockIDocDocument.getMessageType()).thenReturn(MESSAGE_TYPE_VALUE);
		when(mockIDocDocument.getOutputMode()).thenReturn(OUTPUT_MODE_VALUE);
		when(mockIDocDocument.getRecipientAddress()).thenReturn(RECIPIENT_ADDRESS_VALUE);
		when(mockIDocDocument.getRecipientLogicalAddress()).thenReturn(RECIPIENT_LOGICAL_ADDRESS_VALUE);
		when(mockIDocDocument.getRecipientPartnerFunction()).thenReturn(RECIPIENT_PARTNER_FUNCTION_VALUE);
		when(mockIDocDocument.getRecipientPartnerType()).thenReturn(RECIPIENT_PARTNER_TYPE_VALUE);
		when(mockIDocDocument.getRecipientPartnerNumber()).thenReturn(RECIPIENT_PARTNER_NUMBER_VALUE);
		when(mockIDocDocument.getRecipientPort()).thenReturn(RECIPIENT_PORT_VALUE);
		when(mockIDocDocument.getSenderAddress()).thenReturn(SENDER_ADDRESS_VALUE);
		when(mockIDocDocument.getSenderLogicalAddress()).thenReturn(SENDER_LOGICAL_ADDRESS_VALUE);
		when(mockIDocDocument.getSenderPartnerFunction()).thenReturn(SENDER_PARTNER_FUNCTION_VALUE);
		when(mockIDocDocument.getSenderPartnerNumber()).thenReturn(SENDER_PARTNER_NUMBER_VALUE);
		when(mockIDocDocument.getSenderPartnerType()).thenReturn(SENDER_PARTNER_TYPE_VALUE);
		when(mockIDocDocument.getSenderPort()).thenReturn(SENDER_PORT_VALUE);
		when(mockIDocDocument.getSerialization()).thenReturn(SERIALIZATION_VALUE);
		when(mockIDocDocument.getStatus()).thenReturn(STATUS_VALUE);
		when(mockIDocDocument.getTestFlag()).thenReturn(TEST_FLAG_VALUE);
		
		
	}
	
	public void enhanceSegmentRecordMetaData(IDocRecordMetaData mockRecordMetaData) throws Exception {
		
		when(mockRecordMetaData.getName(0)).thenReturn(CHAR_FIELD);
		when(mockRecordMetaData.getDataTypeName(CHAR_FIELD)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataTypeName(0)).thenReturn("CHAR");
		when(mockRecordMetaData.getDatatype(CHAR_FIELD)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getDatatype(0)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getType(0)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(0)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(0)).thenReturn("FIELD0_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(0)).thenReturn("FIELD0_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(0)).thenReturn("FIELD0_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(0)).thenReturn("FIELD0_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(0)).thenReturn(16);
		when(mockRecordMetaData.getLength(0)).thenReturn(16);
		when(mockRecordMetaData.getLength(CHAR_FIELD)).thenReturn(16);
		when(mockRecordMetaData.getOffset(0)).thenReturn(0);
		when(mockRecordMetaData.getOutputLength(0)).thenReturn(16);
		when(mockRecordMetaData.getValues(0)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(0)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(0)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(0)).thenReturn(false);
		
		when(mockRecordMetaData.getName(1)).thenReturn(QUAN_FIELD);
		when(mockRecordMetaData.getDataTypeName(QUAN_FIELD)).thenReturn("QUAN");
		when(mockRecordMetaData.getDataTypeName(1)).thenReturn("QUAN");
		when(mockRecordMetaData.getDatatype(QUAN_FIELD)).thenReturn(IDocDatatype.DECIMAL);
		when(mockRecordMetaData.getDatatype(1)).thenReturn(IDocDatatype.DECIMAL);
		when(mockRecordMetaData.getType(1)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(1)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(1)).thenReturn("FIELD1_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(1)).thenReturn("FIELD1_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(1)).thenReturn("FIELD1_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(1)).thenReturn("FIELD1_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(1)).thenReturn(19);
		when(mockRecordMetaData.getLength(1)).thenReturn(19);
		when(mockRecordMetaData.getLength(QUAN_FIELD)).thenReturn(19);
		when(mockRecordMetaData.getOffset(1)).thenReturn(16);
		when(mockRecordMetaData.getOutputLength(1)).thenReturn(19);
		when(mockRecordMetaData.getValues(1)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(1)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(1)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(1)).thenReturn(false);

		when(mockRecordMetaData.getName(2)).thenReturn(UNIT_FIELD);
		when(mockRecordMetaData.getDataTypeName(UNIT_FIELD)).thenReturn("UNIT");
		when(mockRecordMetaData.getDataTypeName(2)).thenReturn("UNIT");
		when(mockRecordMetaData.getDatatype(UNIT_FIELD)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getDatatype(2)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getType(2)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(2)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(2)).thenReturn("FIELD2_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(2)).thenReturn("FIELD2_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(2)).thenReturn("FIELD2_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(2)).thenReturn("FIELD2_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(2)).thenReturn(3);
		when(mockRecordMetaData.getLength(2)).thenReturn(3);
		when(mockRecordMetaData.getLength(UNIT_FIELD)).thenReturn(3);
		when(mockRecordMetaData.getOffset(2)).thenReturn(35);
		when(mockRecordMetaData.getOutputLength(2)).thenReturn(3);
		when(mockRecordMetaData.getValues(2)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(2)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(2)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(2)).thenReturn(false);

		when(mockRecordMetaData.getName(3)).thenReturn(NUMC_FIELD);
		when(mockRecordMetaData.getDataTypeName(NUMC_FIELD)).thenReturn("NUMC");
		when(mockRecordMetaData.getDataTypeName(3)).thenReturn("NUMC");
		when(mockRecordMetaData.getDatatype(NUMC_FIELD)).thenReturn(IDocDatatype.NUMERIC);
		when(mockRecordMetaData.getDatatype(3)).thenReturn(IDocDatatype.NUMERIC);
		when(mockRecordMetaData.getType(3)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(3)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(3)).thenReturn("FIELD3_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(3)).thenReturn("FIELD3_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(3)).thenReturn("FIELD3_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(3)).thenReturn("FIELD3_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(3)).thenReturn(10);
		when(mockRecordMetaData.getLength(3)).thenReturn(10);
		when(mockRecordMetaData.getLength(NUMC_FIELD)).thenReturn(10);
		when(mockRecordMetaData.getOffset(3)).thenReturn(38);
		when(mockRecordMetaData.getOutputLength(3)).thenReturn(10);
		when(mockRecordMetaData.getValues(3)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(3)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(3)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(3)).thenReturn(false);
		
		when(mockRecordMetaData.getName(4)).thenReturn(DATS_FIELD);
		when(mockRecordMetaData.getDataTypeName(DATS_FIELD)).thenReturn("DATS");
		when(mockRecordMetaData.getDataTypeName(4)).thenReturn("DATS");
		when(mockRecordMetaData.getDatatype(DATS_FIELD)).thenReturn(IDocDatatype.DATE);
		when(mockRecordMetaData.getDatatype(4)).thenReturn(IDocDatatype.DATE);
		when(mockRecordMetaData.getType(4)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(4)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(4)).thenReturn("FIELD4_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(4)).thenReturn("FIELD4_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(4)).thenReturn("FIELD4_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(4)).thenReturn("FIELD4_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(4)).thenReturn(8);
		when(mockRecordMetaData.getLength(4)).thenReturn(8);
		when(mockRecordMetaData.getLength(DATS_FIELD)).thenReturn(8);
		when(mockRecordMetaData.getOffset(4)).thenReturn(48);
		when(mockRecordMetaData.getOutputLength(4)).thenReturn(8);
		when(mockRecordMetaData.getValues(4)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(4)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(4)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(4)).thenReturn(false);

		when(mockRecordMetaData.getName(5)).thenReturn(TIMS_FIELD);
		when(mockRecordMetaData.getDataTypeName(TIMS_FIELD)).thenReturn("TIMS");
		when(mockRecordMetaData.getDataTypeName(5)).thenReturn("TIMS");
		when(mockRecordMetaData.getDatatype(TIMS_FIELD)).thenReturn(IDocDatatype.TIME);
		when(mockRecordMetaData.getDatatype(5)).thenReturn(IDocDatatype.TIME);
		when(mockRecordMetaData.getType(5)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(5)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(5)).thenReturn("FIELD5_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(5)).thenReturn("FIELD5_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(5)).thenReturn("FIELD5_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(5)).thenReturn("FIELD5_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(5)).thenReturn(6);
		when(mockRecordMetaData.getLength(5)).thenReturn(6);
		when(mockRecordMetaData.getLength(TIMS_FIELD)).thenReturn(6);
		when(mockRecordMetaData.getOffset(5)).thenReturn(56);
		when(mockRecordMetaData.getOutputLength(5)).thenReturn(6);
		when(mockRecordMetaData.getValues(5)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(5)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(5)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(5)).thenReturn(false);

		when(mockRecordMetaData.getName(6)).thenReturn(CURR_FIELD);
		when(mockRecordMetaData.getDataTypeName(CURR_FIELD)).thenReturn("CURR");
		when(mockRecordMetaData.getDataTypeName(6)).thenReturn("CURR");
		when(mockRecordMetaData.getDatatype(CURR_FIELD)).thenReturn(IDocDatatype.DECIMAL);
		when(mockRecordMetaData.getDatatype(6)).thenReturn(IDocDatatype.DECIMAL);
		when(mockRecordMetaData.getType(6)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(6)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(6)).thenReturn("FIELD6_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(6)).thenReturn("FIELD6_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(6)).thenReturn("FIELD6_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(6)).thenReturn("FIELD6_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(6)).thenReturn(19);
		when(mockRecordMetaData.getLength(6)).thenReturn(19);
		when(mockRecordMetaData.getLength(CURR_FIELD)).thenReturn(19);
		when(mockRecordMetaData.getOffset(6)).thenReturn(62);
		when(mockRecordMetaData.getOutputLength(6)).thenReturn(19);
		when(mockRecordMetaData.getValues(6)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(6)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(6)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(6)).thenReturn(false);

		when(mockRecordMetaData.getName(7)).thenReturn(CUKY_FIELD);
		when(mockRecordMetaData.getDataTypeName(CUKY_FIELD)).thenReturn("CUKY");
		when(mockRecordMetaData.getDataTypeName(7)).thenReturn("CUKY");
		when(mockRecordMetaData.getDatatype(CUKY_FIELD)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getDatatype(7)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getType(7)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(7)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(7)).thenReturn("FIELD7_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(7)).thenReturn("FIELD7_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(7)).thenReturn("FIELD7_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(7)).thenReturn("FIELD7_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(7)).thenReturn(5);
		when(mockRecordMetaData.getLength(7)).thenReturn(5);
		when(mockRecordMetaData.getLength(CUKY_FIELD)).thenReturn(5);
		when(mockRecordMetaData.getOffset(7)).thenReturn(81);
		when(mockRecordMetaData.getOutputLength(7)).thenReturn(5);
		when(mockRecordMetaData.getValues(7)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(7)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(7)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(7)).thenReturn(false);

		when(mockRecordMetaData.getName(8)).thenReturn(LANG_FIELD);
		when(mockRecordMetaData.getDataTypeName(LANG_FIELD)).thenReturn("LANG");
		when(mockRecordMetaData.getDataTypeName(8)).thenReturn("LANG");
		when(mockRecordMetaData.getDatatype(LANG_FIELD)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getDatatype(8)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getType(8)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(8)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(8)).thenReturn("FIELD8_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(8)).thenReturn("FIELD8_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(8)).thenReturn("FIELD8_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(8)).thenReturn("FIELD8_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(8)).thenReturn(2);
		when(mockRecordMetaData.getLength(8)).thenReturn(2);
		when(mockRecordMetaData.getLength(LANG_FIELD)).thenReturn(2);
		when(mockRecordMetaData.getOffset(8)).thenReturn(86);
		when(mockRecordMetaData.getOutputLength(8)).thenReturn(2);
		when(mockRecordMetaData.getValues(8)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(8)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(8)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(8)).thenReturn(false);

		when(mockRecordMetaData.getName(9)).thenReturn(CLNT_FIELD);
		when(mockRecordMetaData.getDataTypeName(CLNT_FIELD)).thenReturn("CLNT");
		when(mockRecordMetaData.getDataTypeName(9)).thenReturn("CLNT");
		when(mockRecordMetaData.getDatatype(CLNT_FIELD)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getDatatype(9)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getType(9)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(9)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(9)).thenReturn("FIELD9_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(9)).thenReturn("FIELD9_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(9)).thenReturn("FIELD9_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(9)).thenReturn("FIELD9_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(9)).thenReturn(3);
		when(mockRecordMetaData.getLength(9)).thenReturn(3);
		when(mockRecordMetaData.getLength(CLNT_FIELD)).thenReturn(3);
		when(mockRecordMetaData.getOffset(9)).thenReturn(88);
		when(mockRecordMetaData.getOutputLength(9)).thenReturn(3);
		when(mockRecordMetaData.getValues(9)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(9)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(9)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(9)).thenReturn(false);

		when(mockRecordMetaData.getName(10)).thenReturn(INT1_FIELD);
		when(mockRecordMetaData.getDataTypeName(INT1_FIELD)).thenReturn("INT1");
		when(mockRecordMetaData.getDataTypeName(10)).thenReturn("INT1");
		when(mockRecordMetaData.getDatatype(INT1_FIELD)).thenReturn(IDocDatatype.INTEGER);
		when(mockRecordMetaData.getDatatype(10)).thenReturn(IDocDatatype.INTEGER);
		when(mockRecordMetaData.getType(10)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(10)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(10)).thenReturn("FIELD10_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(10)).thenReturn("FIELD10_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(10)).thenReturn("FIELD10_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(10)).thenReturn("FIELD10_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(10)).thenReturn(3);
		when(mockRecordMetaData.getLength(10)).thenReturn(3);
		when(mockRecordMetaData.getLength(INT1_FIELD)).thenReturn(3);
		when(mockRecordMetaData.getOffset(10)).thenReturn(91);
		when(mockRecordMetaData.getOutputLength(10)).thenReturn(3);
		when(mockRecordMetaData.getValues(10)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(10)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(10)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(10)).thenReturn(false);

		when(mockRecordMetaData.getName(11)).thenReturn(INT2_FIELD);
		when(mockRecordMetaData.getDataTypeName(INT2_FIELD)).thenReturn("INT2");
		when(mockRecordMetaData.getDataTypeName(11)).thenReturn("INT2");
		when(mockRecordMetaData.getDatatype(INT2_FIELD)).thenReturn(IDocDatatype.INTEGER);
		when(mockRecordMetaData.getDatatype(11)).thenReturn(IDocDatatype.INTEGER);
		when(mockRecordMetaData.getType(11)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(11)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(11)).thenReturn("FIELD11_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(11)).thenReturn("FIELD11_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(11)).thenReturn("FIELD11_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(11)).thenReturn("FIELD11_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(11)).thenReturn(5);
		when(mockRecordMetaData.getLength(11)).thenReturn(5);
		when(mockRecordMetaData.getLength(INT2_FIELD)).thenReturn(5);
		when(mockRecordMetaData.getOffset(11)).thenReturn(94);
		when(mockRecordMetaData.getOutputLength(11)).thenReturn(5);
		when(mockRecordMetaData.getValues(11)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(11)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(11)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(11)).thenReturn(false);

		when(mockRecordMetaData.getName(12)).thenReturn(INT4_FIELD);
		when(mockRecordMetaData.getDataTypeName(INT4_FIELD)).thenReturn("INT4");
		when(mockRecordMetaData.getDataTypeName(12)).thenReturn("INT4");
		when(mockRecordMetaData.getDatatype(INT4_FIELD)).thenReturn(IDocDatatype.INTEGER);
		when(mockRecordMetaData.getDatatype(12)).thenReturn(IDocDatatype.INTEGER);
		when(mockRecordMetaData.getType(12)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(12)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(12)).thenReturn("FIELD12_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(12)).thenReturn("FIELD12_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(12)).thenReturn("FIELD12_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(12)).thenReturn("FIELD12_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(12)).thenReturn(10);
		when(mockRecordMetaData.getLength(12)).thenReturn(10);
		when(mockRecordMetaData.getLength(INT4_FIELD)).thenReturn(10);
		when(mockRecordMetaData.getOffset(12)).thenReturn(99);
		when(mockRecordMetaData.getOutputLength(12)).thenReturn(10);
		when(mockRecordMetaData.getValues(12)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(12)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(12)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(12)).thenReturn(false);

		when(mockRecordMetaData.getName(13)).thenReturn(FLTP_FIELD);
		when(mockRecordMetaData.getDataTypeName(FLTP_FIELD)).thenReturn("FLTP");
		when(mockRecordMetaData.getDataTypeName(13)).thenReturn("FLTP");
		when(mockRecordMetaData.getDatatype(FLTP_FIELD)).thenReturn(IDocDatatype.DECIMAL);
		when(mockRecordMetaData.getDatatype(13)).thenReturn(IDocDatatype.DECIMAL);
		when(mockRecordMetaData.getType(13)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(13)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(13)).thenReturn("FIELD13_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(13)).thenReturn("FIELD13_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(13)).thenReturn("FIELD13_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(13)).thenReturn("FIELD13_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(13)).thenReturn(16);
		when(mockRecordMetaData.getLength(13)).thenReturn(16);
		when(mockRecordMetaData.getLength(FLTP_FIELD)).thenReturn(16);
		when(mockRecordMetaData.getOffset(13)).thenReturn(109);
		when(mockRecordMetaData.getOutputLength(13)).thenReturn(16);
		when(mockRecordMetaData.getValues(13)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(13)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(13)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(13)).thenReturn(false);

		when(mockRecordMetaData.getName(14)).thenReturn(ACCP_FIELD);
		when(mockRecordMetaData.getDataTypeName(ACCP_FIELD)).thenReturn("ACCP");
		when(mockRecordMetaData.getDataTypeName(14)).thenReturn("ACCP");
		when(mockRecordMetaData.getDatatype(ACCP_FIELD)).thenReturn(IDocDatatype.NUMERIC);
		when(mockRecordMetaData.getDatatype(14)).thenReturn(IDocDatatype.NUMERIC);
		when(mockRecordMetaData.getType(14)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(14)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(14)).thenReturn("FIELD14_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(14)).thenReturn("FIELD14_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(14)).thenReturn("FIELD14_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(14)).thenReturn("FIELD14_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(14)).thenReturn(6);
		when(mockRecordMetaData.getLength(14)).thenReturn(6);
		when(mockRecordMetaData.getLength(ACCP_FIELD)).thenReturn(6);
		when(mockRecordMetaData.getOffset(14)).thenReturn(125);
		when(mockRecordMetaData.getOutputLength(14)).thenReturn(6);
		when(mockRecordMetaData.getValues(14)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(14)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(14)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(14)).thenReturn(false);

		when(mockRecordMetaData.getName(15)).thenReturn(PREC_FIELD);
		when(mockRecordMetaData.getDataTypeName(PREC_FIELD)).thenReturn("PREC");
		when(mockRecordMetaData.getDataTypeName(15)).thenReturn("PREC");
		when(mockRecordMetaData.getDatatype(PREC_FIELD)).thenReturn(IDocDatatype.NUMERIC);
		when(mockRecordMetaData.getDatatype(15)).thenReturn(IDocDatatype.NUMERIC);
		when(mockRecordMetaData.getType(15)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(15)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(15)).thenReturn("FIELD15_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(15)).thenReturn("FIELD15_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(15)).thenReturn("FIELD15_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(15)).thenReturn("FIELD15_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(15)).thenReturn(2);
		when(mockRecordMetaData.getLength(15)).thenReturn(2);
		when(mockRecordMetaData.getLength(PREC_FIELD)).thenReturn(2);
		when(mockRecordMetaData.getOffset(15)).thenReturn(131);
		when(mockRecordMetaData.getOutputLength(15)).thenReturn(2);
		when(mockRecordMetaData.getValues(15)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(15)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(15)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(15)).thenReturn(false);

		when(mockRecordMetaData.getName(16)).thenReturn(LRAW_FIELD);
		when(mockRecordMetaData.getDataTypeName(LRAW_FIELD)).thenReturn("LRAW");
		when(mockRecordMetaData.getDataTypeName(16)).thenReturn("LRAW");
		when(mockRecordMetaData.getDatatype(LRAW_FIELD)).thenReturn(IDocDatatype.BINARY);
		when(mockRecordMetaData.getDatatype(16)).thenReturn(IDocDatatype.BINARY);
		when(mockRecordMetaData.getType(16)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(16)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(16)).thenReturn("FIELD16_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(16)).thenReturn("FIELD16_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(16)).thenReturn("FIELD16_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(16)).thenReturn("FIELD16_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(16)).thenReturn(10);
		when(mockRecordMetaData.getLength(16)).thenReturn(10);
		when(mockRecordMetaData.getLength(LRAW_FIELD)).thenReturn(10);
		when(mockRecordMetaData.getOffset(16)).thenReturn(133);
		when(mockRecordMetaData.getOutputLength(16)).thenReturn(10);
		when(mockRecordMetaData.getValues(16)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(16)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(16)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(16)).thenReturn(false);

		when(mockRecordMetaData.getName(17)).thenReturn(DEC_FIELD);
		when(mockRecordMetaData.getDataTypeName(DEC_FIELD)).thenReturn("DEC");
		when(mockRecordMetaData.getDataTypeName(17)).thenReturn("DEC");
		when(mockRecordMetaData.getDatatype(DEC_FIELD)).thenReturn(IDocDatatype.DECIMAL);
		when(mockRecordMetaData.getDatatype(17)).thenReturn(IDocDatatype.DECIMAL);
		when(mockRecordMetaData.getType(17)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(17)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(17)).thenReturn("FIELD17_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(17)).thenReturn("FIELD17_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(17)).thenReturn("FIELD17_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(17)).thenReturn("FIELD17_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(17)).thenReturn(10);
		when(mockRecordMetaData.getLength(17)).thenReturn(10);
		when(mockRecordMetaData.getLength(DEC_FIELD)).thenReturn(10);
		when(mockRecordMetaData.getOffset(17)).thenReturn(143);
		when(mockRecordMetaData.getOutputLength(17)).thenReturn(10);
		when(mockRecordMetaData.getValues(17)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(17)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(17)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(17)).thenReturn(false);

		when(mockRecordMetaData.getName(18)).thenReturn(RAW_FIELD);
		when(mockRecordMetaData.getDataTypeName(RAW_FIELD)).thenReturn("RAW");
		when(mockRecordMetaData.getDataTypeName(18)).thenReturn("RAW");
		when(mockRecordMetaData.getDatatype(RAW_FIELD)).thenReturn(IDocDatatype.BINARY);
		when(mockRecordMetaData.getDatatype(18)).thenReturn(IDocDatatype.BINARY);
		when(mockRecordMetaData.getType(18)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(18)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(18)).thenReturn("FIELD18_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(18)).thenReturn("FIELD18_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(18)).thenReturn("FIELD18_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(18)).thenReturn("FIELD18_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(18)).thenReturn(10);
		when(mockRecordMetaData.getLength(18)).thenReturn(10);
		when(mockRecordMetaData.getLength(RAW_FIELD)).thenReturn(10);
		when(mockRecordMetaData.getOffset(18)).thenReturn(153);
		when(mockRecordMetaData.getOutputLength(18)).thenReturn(10);
		when(mockRecordMetaData.getValues(18)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(18)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(18)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(18)).thenReturn(false);

		when(mockRecordMetaData.getName(19)).thenReturn(STRING_FIELD);
		when(mockRecordMetaData.getDataTypeName(STRING_FIELD)).thenReturn("STRING");
		when(mockRecordMetaData.getDataTypeName(19)).thenReturn("STRING");
		when(mockRecordMetaData.getDatatype(STRING_FIELD)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getDatatype(19)).thenReturn(IDocDatatype.STRING);
		when(mockRecordMetaData.getType(19)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(19)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(19)).thenReturn("FIELD19_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(19)).thenReturn("FIELD19_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(19)).thenReturn("FIELD19_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(19)).thenReturn("FIELD19_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(19)).thenReturn(10);
		when(mockRecordMetaData.getLength(19)).thenReturn(10);
		when(mockRecordMetaData.getLength(STRING_FIELD)).thenReturn(10);
		when(mockRecordMetaData.getOffset(19)).thenReturn(163);
		when(mockRecordMetaData.getOutputLength(19)).thenReturn(10);
		when(mockRecordMetaData.getValues(19)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(19)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(19)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(19)).thenReturn(false);

		when(mockRecordMetaData.getName(20)).thenReturn(RAWSTRING_FIELD);
		when(mockRecordMetaData.getDataTypeName(RAWSTRING_FIELD)).thenReturn("RAWSTRING");
		when(mockRecordMetaData.getDataTypeName(20)).thenReturn("RAWSTRING");
		when(mockRecordMetaData.getDatatype(RAWSTRING_FIELD)).thenReturn(IDocDatatype.BINARY);
		when(mockRecordMetaData.getDatatype(20)).thenReturn(IDocDatatype.BINARY);
		when(mockRecordMetaData.getType(20)).thenReturn(IDocRecordMetaData.TYPE_CHAR);
		when(mockRecordMetaData.getTypeAsString(20)).thenReturn("CHAR");
		when(mockRecordMetaData.getDataElementName(20)).thenReturn("FIELD20_DATA_ELEMENT");
		when(mockRecordMetaData.getDomainName(20)).thenReturn("FIELD20_DOMAIN_NAME");
		when(mockRecordMetaData.getDescription(20)).thenReturn("FIELD20_DATA_ELEMENT Description");
		when(mockRecordMetaData.getCheckTableName(20)).thenReturn("FIELD20_CHECK_TABLE_NAME");
		when(mockRecordMetaData.getInternalLength(20)).thenReturn(10);
		when(mockRecordMetaData.getLength(20)).thenReturn(10);
		when(mockRecordMetaData.getLength(RAWSTRING_FIELD)).thenReturn(10);
		when(mockRecordMetaData.getOffset(20)).thenReturn(173);
		when(mockRecordMetaData.getOutputLength(20)).thenReturn(10);
		when(mockRecordMetaData.getValues(20)).thenReturn(RECORD_FIELD_VALUES);
		when(mockRecordMetaData.getValueDescriptions(20)).thenReturn(RECORD_FIELD_VALUE_DESCRIPTIONS);
		when(mockRecordMetaData.getValueRanges(20)).thenReturn(RECORD_FIELD_VALUE_RANGES);
		when(mockRecordMetaData.isISOCode(20)).thenReturn(false);

	}
	
	@Override
	public void doPreSetup() throws Exception {
		super.doPreSetup();
		
		createMocks();
		
		/* Enhance Destination mock */
		when(mockDestination.createTID()).thenReturn(TEST_TID);
		
		/* Enhance IDoc Server mock */
		when(mockIDocServer.getRepositoryDestination()).thenReturn(TEST_DEST);
		when(mockIDocServer.getProgramID()).thenReturn(TEST_PROGRAM_ID);
		when(mockIDocServer.getIDocHandlerFactory()).thenReturn(mockIDocHandlerFactory);
		when(mockIDocServer.getIDocRepository()).thenReturn(mockIDocRepository);
		when(mockIDocServer.getState()).thenReturn(JCoServerState.STOPPED);
		
		/* Enhance IDoc Repository  mock */
		when(mockIDocRepository.getName()).thenReturn(TEST_REPOSITORY);
		when(mockIDocRepository.getRootSegmentMetaData(TEST_IDOC_TYPE, TEST_IDOC_TYPE_EXTENSION, TEST_SYSTEM_RELEASE, TEST_APPLICATION_RELEASE)).thenReturn(mockRootSegmentMetaData);
		
		/* Enhance IDoc Factory mock */
		when(mockIDocFactory.createIDocDocument(mockIDocRepository, TEST_IDOC_TYPE, TEST_IDOC_TYPE_EXTENSION, TEST_SYSTEM_RELEASE, TEST_APPLICATION_RELEASE)).thenReturn(mockIDocDocument);
		when(mockIDocFactory.createIDocDocumentList(mockIDocRepository, TEST_IDOC_TYPE, TEST_IDOC_TYPE_EXTENSION, TEST_SYSTEM_RELEASE, TEST_APPLICATION_RELEASE)).thenReturn(mockIDocDocumentList);
		
		/* Enhance IDoc Document List mock */
		when(mockIDocDocumentList.iterator()).thenReturn(mockIDocDocumentListIterator);
		when(mockIDocDocumentList.getIDocType()).thenReturn(TEST_IDOC_TYPE);
		when(mockIDocDocumentList.getIDocTypeExtension()).thenReturn(TEST_IDOC_TYPE_EXTENSION);
		when(mockIDocDocumentList.getSystemRelease()).thenReturn(TEST_SYSTEM_RELEASE);
		when(mockIDocDocumentList.getApplicationRelease()).thenReturn(TEST_APPLICATION_RELEASE);
		when(mockIDocDocumentList.addNew()).thenReturn(mockIDocDocument);
		
		/* Enhance IDoc Document List Iterator mock */
		when(mockIDocDocumentListIterator.hasNext()).thenReturn(true).thenReturn(true).thenReturn(false);
		when(mockIDocDocumentListIterator.next()).thenReturn(mockIDocDocument).thenReturn(mockIDocDocument).thenThrow(new NoSuchElementException());
		
		enhanceRootSegment();
		enhanceLevel1Segment();
		enhanceLevel2Segment();
		enhanceLevel3Segment();
		enhanceIDocDocument();		
	}
	
	protected Document createAndPopulateDocument() throws Exception {
		Document document = IDocUtil.createDocument(mockIDocRepository, TEST_IDOC_TYPE, TEST_IDOC_TYPE_EXTENSION, TEST_SYSTEM_RELEASE, TEST_APPLICATION_RELEASE);
		
		document.setArchiveKey(ARCHIVE_KEY_VALUE); 
		document.setClient(CLIENT_VALUE);
		document.setCreationDate(DATE_VALUE.getTime());
		document.setCreationTime(TIME_VALUE.getTime());
		document.setDirection(DIRECTION_VALUE);
		document.setEDIMessage(EDI_MESSAGE_VALUE);
		document.setEDIMessageGroup(EDI_MESSAGE_GROUP_VALUE);
		document.setEDIMessageType(EDI_MESSAGE_TYPE_VALUE);
		document.setEDIStandardFlag(EDI_STANDARD_FLAG_VALUE);
		document.setEDIStandardVersion(EDI_STANDARD_VERSION_VALUE);
		document.setEDITransmissionFile(EDI_TRANSMISSION_FILE_VALUE);
		document.setIDocCompoundType(IDOC_COMPOUND_TYPE_VALUE);
		document.setIDocNumber(IDOC_NUMBER_VALUE);
		document.setIDocSAPRelease(IDOC_SAP_RELEASE_VALUE);
		document.setIDocType(IDOC_TYPE_VALUE);
		document.setIDocTypeExtension(IDOC_TYPE_EXTENSION_VALUE);
		document.setMessageCode(MESSAGE_CODE_VALUE);
		document.setMessageFunction(MESSAGE_FUNCTION_VALUE);
		document.setMessageType(MESSAGE_TYPE_VALUE);
		document.setOutputMode(OUTPUT_MODE_VALUE);
		document.setRecipientAddress(RECIPIENT_ADDRESS_VALUE);
		document.setRecipientLogicalAddress(RECIPIENT_LOGICAL_ADDRESS_VALUE);
		document.setRecipientPartnerFunction(RECIPIENT_PARTNER_FUNCTION_VALUE);
		document.setRecipientPartnerNumber(RECIPIENT_PARTNER_NUMBER_VALUE);
		document.setRecipientPartnerType(RECIPIENT_PARTNER_TYPE_VALUE);
		document.setRecipientPort(RECIPIENT_PORT_VALUE);
		document.setSenderAddress(SENDER_ADDRESS_VALUE);
		document.setSenderLogicalAddress(SENDER_LOGICAL_ADDRESS_VALUE);
		document.setSenderPartnerFunction(SENDER_PARTNER_FUNCTION_VALUE);
		document.setSenderPartnerNumber(SENDER_PARTNER_NUMBER_VALUE);
		document.setSenderPartnerType(SENDER_PARTNER_TYPE_VALUE);
		document.setSenderPort(SENDER_PORT_VALUE);
		document.setSerialization(SERIALIZATION_VALUE);
		document.setStatus(STATUS_VALUE);
		document.setTestFlag(TEST_FLAG_VALUE);
		
		Segment rootSegment = document.getRootSegment();
		
		Segment level1Segment = rootSegment.getChildren(LEVEL1).add();
		level1Segment.put(CHAR_FIELD, CHAR_FIELD_VALUE);
		level1Segment.put(QUAN_FIELD, QUAN_FIELD_VALUE);
		level1Segment.put(UNIT_FIELD, UNIT_FIELD_VALUE);
		level1Segment.put(NUMC_FIELD, NUMC_FIELD_VALUE);
		level1Segment.put(DATS_FIELD, DATS_FIELD_VALUE);
		level1Segment.put(TIMS_FIELD, TIMS_FIELD_VALUE);
		level1Segment.put(CURR_FIELD, CURR_FIELD_VALUE);
		level1Segment.put(CUKY_FIELD, CUKY_FIELD_VALUE);
		level1Segment.put(LANG_FIELD, LANG_FIELD_VALUE);
		level1Segment.put(CLNT_FIELD, CLNT_FIELD_VALUE);
		level1Segment.put(INT1_FIELD, INT1_FIELD_VALUE);
		level1Segment.put(INT2_FIELD, INT2_FIELD_VALUE);
		level1Segment.put(INT4_FIELD, INT4_FIELD_VALUE);
		level1Segment.put(FLTP_FIELD, FLTP_FIELD_VALUE);
		level1Segment.put(ACCP_FIELD, ACCP_FIELD_VALUE);
		level1Segment.put(PREC_FIELD, PREC_FIELD_VALUE);
		level1Segment.put(LRAW_FIELD, LRAW_FIELD_VALUE);
		level1Segment.put(DEC_FIELD, DEC_FIELD_VALUE);
		level1Segment.put(RAW_FIELD, RAW_FIELD_VALUE);
		level1Segment.put(STRING_FIELD, STRING_FIELD_VALUE);
		level1Segment.put(RAWSTRING_FIELD, RAWSTRING_FIELD_VALUE);
		
		Segment level2Segment = level1Segment.getChildren(LEVEL2).add();
		level2Segment.put(CHAR_FIELD, CHAR_FIELD_VALUE);
		level2Segment.put(QUAN_FIELD, QUAN_FIELD_VALUE);
		level2Segment.put(UNIT_FIELD, UNIT_FIELD_VALUE);
		level2Segment.put(NUMC_FIELD, NUMC_FIELD_VALUE);
		level2Segment.put(DATS_FIELD, DATS_FIELD_VALUE);
		level2Segment.put(TIMS_FIELD, TIMS_FIELD_VALUE);
		level2Segment.put(CURR_FIELD, CURR_FIELD_VALUE);
		level2Segment.put(CUKY_FIELD, CUKY_FIELD_VALUE);
		level2Segment.put(LANG_FIELD, LANG_FIELD_VALUE);
		level2Segment.put(CLNT_FIELD, CLNT_FIELD_VALUE);
		level2Segment.put(INT1_FIELD, INT1_FIELD_VALUE);
		level2Segment.put(INT2_FIELD, INT2_FIELD_VALUE);
		level2Segment.put(INT4_FIELD, INT4_FIELD_VALUE);
		level2Segment.put(FLTP_FIELD, FLTP_FIELD_VALUE);
		level2Segment.put(ACCP_FIELD, ACCP_FIELD_VALUE);
		level2Segment.put(PREC_FIELD, PREC_FIELD_VALUE);
		level2Segment.put(LRAW_FIELD, LRAW_FIELD_VALUE);
		level2Segment.put(DEC_FIELD, DEC_FIELD_VALUE);
		level2Segment.put(RAW_FIELD, RAW_FIELD_VALUE);
		level2Segment.put(STRING_FIELD, STRING_FIELD_VALUE);
		level2Segment.put(RAWSTRING_FIELD, RAWSTRING_FIELD_VALUE);
		
		Segment level3Segment = level2Segment.getChildren(LEVEL3).add();
		level3Segment.put(CHAR_FIELD, CHAR_FIELD_VALUE);
		level3Segment.put(QUAN_FIELD, QUAN_FIELD_VALUE);
		level3Segment.put(UNIT_FIELD, UNIT_FIELD_VALUE);
		level3Segment.put(NUMC_FIELD, NUMC_FIELD_VALUE);
		level3Segment.put(DATS_FIELD, DATS_FIELD_VALUE);
		level3Segment.put(TIMS_FIELD, TIMS_FIELD_VALUE);
		level3Segment.put(CURR_FIELD, CURR_FIELD_VALUE);
		level3Segment.put(CUKY_FIELD, CUKY_FIELD_VALUE);
		level3Segment.put(LANG_FIELD, LANG_FIELD_VALUE);
		level3Segment.put(CLNT_FIELD, CLNT_FIELD_VALUE);
		level3Segment.put(INT1_FIELD, INT1_FIELD_VALUE);
		level3Segment.put(INT2_FIELD, INT2_FIELD_VALUE);
		level3Segment.put(INT4_FIELD, INT4_FIELD_VALUE);
		level3Segment.put(FLTP_FIELD, FLTP_FIELD_VALUE);
		level3Segment.put(ACCP_FIELD, ACCP_FIELD_VALUE);
		level3Segment.put(PREC_FIELD, PREC_FIELD_VALUE);
		level3Segment.put(LRAW_FIELD, LRAW_FIELD_VALUE);
		level3Segment.put(DEC_FIELD, DEC_FIELD_VALUE);
		level3Segment.put(RAW_FIELD, RAW_FIELD_VALUE);
		level3Segment.put(STRING_FIELD, STRING_FIELD_VALUE);
		level3Segment.put(RAWSTRING_FIELD, RAWSTRING_FIELD_VALUE);
		
		return document;
	}
	
	protected DocumentList createAndPopulateDocumentList() throws Exception {
		DocumentList documentList = IDocUtil.createDocumentList(mockIDocRepository, TEST_IDOC_TYPE, TEST_IDOC_TYPE_EXTENSION, TEST_SYSTEM_RELEASE, TEST_APPLICATION_RELEASE);
		Document document = createAndPopulateDocument();
		documentList.add(document);
		return documentList;
	}

	@Override
	protected AbstractApplicationContext createApplicationContext() {
		return new StaticApplicationContext();
	}

}
