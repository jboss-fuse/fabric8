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

import java.util.Map;

import org.apache.camel.Endpoint;
import org.apache.camel.impl.UriEndpointComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sap.conn.idoc.jco.JCoIDocServer;

/**
 * An SAP component that manages {@link SapTransactionalIDocListServerEndpoint}.
 * 
 * @author William Collins <punkhornsw@gmail.com>
 * 
 */
public class SapTransactionalIDocListServerComponent extends UriEndpointComponent {

	private static final Logger LOG = LoggerFactory.getLogger(SapTransactionalIDocListServerComponent.class);
	
	public SapTransactionalIDocListServerComponent() {
		super(SapTransactionalIDocListServerEndpoint.class);
	}

	@Override
	protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
		if (!uri.startsWith("sap-idoclist-server:")) { 
			throw new IllegalArgumentException("The URI '" +  uri + "' has invalid scheme; should be 'sap-idoclist-server:'");			
		}
		// Parse URI
		String[] uriComponents = remaining.split(":");

		if (uriComponents.length < 2) {
			throw new IllegalArgumentException("URI must be of the form: sap-idoc-server:<server>:<idocType>[:<idocTypeExtension>[:<systemRelease>[:<applicationRelease>]]]");
		}
		
		// Extract URI components
		// Add component specific prefix to server name to scope server configurations to this component.
		parameters.put("serverName", uriComponents[0]);
		parameters.put("idocType", uriComponents[1]);
		if(uriComponents.length > 2) {
			parameters.put("idocTypeExtension", uriComponents[2]);
		}
		if(uriComponents.length > 3) {
			parameters.put("systemRelease", uriComponents[3]);
		}
		if(uriComponents.length > 4) {
			parameters.put("applicationRelease", uriComponents[4]);
		}
		SapTransactionalIDocListServerEndpoint endpoint = new SapTransactionalIDocListServerEndpoint(uri, this);

		// Configure Endpoint
		setProperties(endpoint, parameters);
		LOG.debug("Created endpoint '" + uri + "'");

		// Create a document list to ensure that the data layer's package registry is
		// loaded with the schema of this endpoint's IDoc type.
		endpoint.createDocumentList();
		
		return endpoint;
	}

	protected JCoIDocServer getServer(String serverName) throws Exception {
		return ServerManager.INSTANCE.getServer(serverName);
	}

	protected IDocHandlerFactory getIDocHandlerFactory(String serverName) throws Exception {
		JCoIDocServer server = getServer(serverName);
		if (server == null) {
			return null;
		}
		return (IDocHandlerFactory) server.getIDocHandlerFactory();
	}
	
	@Override
    protected void doStart() throws Exception {
    	super.doStart();
    	ServerManager.INSTANCE.incrementActiveComponentInstances();
    	LOG.debug("STARTED");
   }
    
    @Override
    protected void doStop() throws Exception {
    	ServerManager.INSTANCE.decrementActiveComponentInstances();
    	super.doStop();
    	LOG.debug("STOPPED");
    }
}
