package org.fusesource.camel.component.sap;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.camel.util.StopWatch;
import org.apache.camel.util.TimeUtils;
import org.fusesource.camel.component.sap.converter.DocumentConverter;
import org.fusesource.camel.component.sap.model.idoc.Document;
import org.fusesource.camel.component.sap.model.idoc.Segment;
import org.fusesource.camel.component.sap.model.idoc.SegmentList;
import org.fusesource.camel.component.sap.model.idoc.impl.DocumentImpl;
import org.fusesource.camel.component.sap.model.idoc.impl.SegmentImpl;
import org.fusesource.camel.component.sap.util.IDocUtil;
import org.fusesource.camel.component.sap.util.Util;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentWithNamespaceConverterTest {
	
	private static final Logger LOG = LoggerFactory.getLogger(DocumentWithNamespaceConverterTest.class);

	public static final String DOCUMENT_STRING = 
		"<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
		"<idoc:Document xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:RHT_IDC---=\"http://sap.fusesource.org/idoc/JBF/RHT:IDC///\" xmlns:idoc=\"http://sap.fusesource.org/idoc\" creationDate=\"2017-07-11T13:59:38.494-0400\" creationTime=\"2017-07-11T13:59:38.494-0400\" iDocType=\"/RHT/IDC\" iDocTypeExtension=\"\">" +
		"  <rootSegment xsi:type=\"RHT_IDC---:ROOT\" document=\"/\">" +
		"    <segmentChildren xmlns:RHT=\"http://sap.fusesource.org/idoc/JBF/RHT:IDC///\" parent=\"//@rootSegment\">" +
		"      <RHT:S1 xmlns:RHT=\"http://sap.fusesource.org/idoc/JBF/RHT:IDC///\" parent=\"//@rootSegment\" document=\"/\" RHT:FIELD1=\"A\" RHT:FIELD2=\"B\">" +
		"        <segmentChildren xmlns:RHT=\"http://sap.fusesource.org/idoc/JBF/RHT:IDC///\" parent=\"//@rootSegment/@segmentChildren/@RHT:S1.0\">" +
		"          <RHT:S2 xmlns:RHT=\"http://sap.fusesource.org/idoc/JBF/RHT:IDC///\" parent=\"//@rootSegment/@segmentChildren/@RHT:S1.0\" document=\"/\" RHT:FIELD1=\"A\" RHT:FIELD2=\"B\"/>" +
		"        </segmentChildren>" +
		"      </RHT:S1>" +
		"    </segmentChildren>" +
		"  </rootSegment>" +
		"</idoc:Document>";

	@BeforeClass
	public static void setupClass() throws IOException {
		File file = new File("data/testNamespaceRegistry.ecore");
		Util.loadRegistry(file);
	}

    private TestName testName = new TestName();
    private final StopWatch watch = new StopWatch();

    @Before
    public void setUp() throws Exception {
        LOG.info("********************************************************************************");
        LOG.info("Testing: " + testName.getMethodName() + "(" + getClass().getName() + ")");
        LOG.info("********************************************************************************");
        watch.restart();
   }
    
    @After
    public void tearDown() throws Exception {
        long time = watch.stop();

        LOG.info("********************************************************************************");
        LOG.info("Testing done: " + testName.getMethodName() + "(" + getClass().getName() + ")");
        LOG.info("Took: " + TimeUtils.printDuration(time) + " (" + time + " millis)");
        LOG.info("********************************************************************************");
    }    
    
	@Test
	public void testToDocumentFromString() throws Exception {

		//
		// Given
		//
		
		//
		// When
		//
		
		Document document = DocumentConverter.toDocument(DOCUMENT_STRING); 

		//
		// Then
		//
		
		verifyDocument(document);

	}

	@Test
	public void testToDocumentFormInputStream() throws Exception{

		//
		// Given
		//
		ByteArrayInputStream bais = new ByteArrayInputStream(DOCUMENT_STRING.getBytes("UTF-8"));
		
		//
		// When
		//
		
		Document document = DocumentConverter.toDocument(bais);

		//
		// Then
		//

		verifyDocument(document);

	}

	@Test
	public void testToDocumentFromByteArray() throws Exception {
		
		//
		// Given
		//
		
		//
		// When
		//
		
		Document document = DocumentConverter.toDocument(DOCUMENT_STRING.getBytes("UTF-8"));

		//
		// Then
		//

		verifyDocument(document);

	}

	@Test
	public void testToString() throws Exception {

		//
		// Given
		//

		Document document = createAndPopulateDocument();
		
		//
		// When
		//
		
		String documentString = DocumentConverter.toString((DocumentImpl)document);
		
		//
		// Then
		//

		document = DocumentConverter.toDocument(documentString);
		verifyDocument(document);

	}

	@Test
	public void testToOutputStream() throws Exception {

		//
		// Given
		//
		
		Document document = createAndPopulateDocument();
		
		//
		// When
		//

		OutputStream os = DocumentConverter.toOutputStream((DocumentImpl)document);
		
		//
		// Then
		//
		
		byte[] bytes = ((ByteArrayOutputStream)os).toByteArray();
		document = DocumentConverter.toDocument(bytes);
		verifyDocument(document);
		
	}

	@Test
	public void testToInputStream() throws Exception {

		//
		// Given
		//
		
		Document document = createAndPopulateDocument();
		
		//
		// When
		//
		
		InputStream is = DocumentConverter.toInputStream((DocumentImpl)document);
		
		//
		// Then
		//

		document = DocumentConverter.toDocument(is);
		verifyDocument(document);

	}
	
	public void verifyDocument(Document document) throws Exception {
		assertThat("The document is an unexpected null value",document, notNullValue());
		assertThat("document.getIDocType() returned '" +  document.getIDocType() + "' instead of expected value '" + "/RHT/IDC" + "'", (String) document.getIDocType(), is("/RHT/IDC"));
		
		Segment rootSegment = document.getRootSegment();
		assertThat("document.getRootSegment() returned unexpected null value", rootSegment, notNullValue());
		assertThat("rootSegment.getParent() returned unexpected non null value", rootSegment.getParent(), nullValue());
		
		Segment level1Segment = rootSegment.getChildren().get(0);
		assertThat("rootSegment.getChildren().get(0) returned unexpected null value", level1Segment, notNullValue());
		assertThat("level1Segment.getType() returned unexpected value", level1Segment.getType(), is("/RHT/S1"));
		assertThat("level2Segment.getParent() returned unexpected value", level1Segment.getParent(), is(rootSegment));
		assertThat("level1Segment.get(/RHT/FIELD1) returned unexpected value", (String) level1Segment.get("/RHT/FIELD1"), is("A"));
		assertThat("level1Segment.get(/RHT/FIELD2) returned unexpected value", (String) level1Segment.get("/RHT/FIELD2"), is("B"));

		Segment level2Segment = level1Segment.getChildren().get(0);
		assertThat("level1Segment.getChildren().get(0) returned unexpected null value", level2Segment, notNullValue());
		assertThat("level2Segment.getType() returned unexpected value", level2Segment.getType(), is("/RHT/S2"));
		assertThat("level2Segment.getParent() returned unexpected value", level2Segment.getParent(), is(level1Segment));
		assertThat("level2Segment.get(/RHT/FIELD1) returned unexpected value", (String) level2Segment.get("/RHT/FIELD1"), is("A"));
		assertThat("level2Segment.get(/RHT/FIELD2) returned unexpected value", (String) level2Segment.get("/RHT/FIELD2"), is("B"));
		
	}
	
	protected Document createAndPopulateDocument() throws Exception {
		Document document = IDocUtil.createDocument("JBF", "/RHT/IDC", null, null, null);
		Segment rootSegment = document.getRootSegment();
		
		SegmentList<Segment> rhtS1Segments = rootSegment.getChildren("/RHT/S1");
		Segment rhtS1Segment = rhtS1Segments.add();
		rhtS1Segment.put("/RHT/FIELD1", "A");
		rhtS1Segment.put("/RHT/FIELD2", "B");

		SegmentList<Segment> rhtS2Segments = rhtS1Segment.getChildren("/RHT/S2");
		Segment rhtS2Segment = rhtS2Segments.add();
		rhtS2Segment.put("/RHT/FIELD1", "A");
		rhtS2Segment.put("/RHT/FIELD2", "B");
		
		return document;
	}

}
