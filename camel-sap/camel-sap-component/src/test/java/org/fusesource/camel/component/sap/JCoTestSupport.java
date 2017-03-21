package org.fusesource.camel.component.sap;

import org.apache.camel.test.spring.CamelSpringTestSupport;
import org.fusesource.camel.component.sap.model.rfc.DestinationData;
import org.fusesource.camel.component.sap.model.rfc.DestinationDataStore;
import org.fusesource.camel.component.sap.model.rfc.ServerData;
import org.fusesource.camel.component.sap.model.rfc.ServerDataStore;
import org.fusesource.camel.component.sap.model.rfc.impl.DestinationDataImpl;
import org.fusesource.camel.component.sap.model.rfc.impl.DestinationDataStoreImpl;
import org.fusesource.camel.component.sap.model.rfc.impl.ServerDataImpl;
import org.fusesource.camel.component.sap.model.rfc.impl.ServerDataStoreImpl;
import org.fusesource.camel.component.sap.util.ComponentDestinationDataProvider;
import org.fusesource.camel.component.sap.util.ComponentServerDataProvider;
import org.powermock.api.mockito.PowerMockito;

import com.sap.conn.idoc.jco.JCoIDoc;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.ext.Environment;
import com.sap.conn.jco.server.JCoServerFactory;

public abstract class JCoTestSupport extends CamelSpringTestSupport {
	
	public static final String TEST_DEST = "TEST_DEST";
	public static final String TEST_ASHOST = "TEST_ASHOST";
	public static final String TEST_SYSNR = "TEST_SYSNR";
	public static final String TEST_CLIENT = "TEST_CLIENT";
	public static final String TEST_USER = "TEST_USER";
	public static final String TEST_PASSWD = "TEST_PASSWD";
	public static final String TEST_LANG = "TEST_LANG";
	

	public static final String TEST_SERVER = "TEST_SERVER";
	public static final String TEST_GW_HOST = "TEST_GW_HOST";
	public static final String TEST_GW_SERV = "TEST_GW_SERV";
	public static final String TEST_PROGRAM_ID = "TEST_PROGRAM_ID";
	public static final String TEST_REPOSITORY = "TEST_REPOSITORY";
	public static final String TEST_CONNECTION_COUNT = "2";
	
	static  {

		PowerMockito.mockStatic(Environment.class);
		PowerMockito.mockStatic(JCoIDoc.class);
		PowerMockito.mockStatic(JCoDestinationManager.class);
		PowerMockito.mockStatic(JCoServerFactory.class);

	}

	@Override
	public void doPreSetup() throws Exception {
		super.doPreSetup();

		// Setup test destination data store entry
		DestinationDataStore destinationDataStore = new DestinationDataStoreImpl();
		
		DestinationData destinationData = new DestinationDataImpl();
		destinationData.setAshost(TEST_ASHOST);
		destinationData.setSysnr(TEST_SYSNR);
		destinationData.setClient(TEST_CLIENT);
		destinationData.setUser(TEST_USER);
		destinationData.setPasswd(TEST_PASSWD);
		destinationData.setLang(TEST_LANG);
		
		destinationDataStore.getEntries().put(TEST_DEST, destinationData);
		
		ComponentDestinationDataProvider.INSTANCE.addDestinationDataStore(destinationDataStore);
		
		// Setup test server data store entry
		ServerDataStore serverDataStore = new ServerDataStoreImpl();
		
		ServerData serverData = new ServerDataImpl();
		serverData.setGwhost(TEST_GW_HOST);
		serverData.setGwserv(TEST_GW_SERV);
		serverData.setProgid(TEST_PROGRAM_ID);
		serverData.setRepositoryDestination(TEST_REPOSITORY);
		serverData.setConnectionCount(TEST_CONNECTION_COUNT);
		
		serverDataStore.getEntries().put(TEST_SERVER, serverData);
		
		ComponentServerDataProvider.INSTANCE.addServerDataStore(serverDataStore);
	}
}
