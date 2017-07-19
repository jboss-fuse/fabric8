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

import org.apache.camel.Endpoint;
import org.apache.camel.impl.UriEndpointComponent;

import com.sap.conn.jco.server.JCoServer;

/**
 * An SAP component that manages {@link SapTransactionalRfcServerEndpoint}
 * .
 * 
 * @author William Collins <punkhornsw@gmail.com>
 * 
 */
public abstract class SapRfcServerComponent extends UriEndpointComponent {

	public SapRfcServerComponent(Class<? extends Endpoint> endpointClass) {
		super(endpointClass);
	}

	protected FunctionHandlerFactory getServerHandlerFactory(String serverName) throws Exception {
		JCoServer server = getServer(serverName);
		if (server == null) {
			return null;
		}
		return (FunctionHandlerFactory) server.getCallHandlerFactory();
	}

	protected JCoServer getServer(String serverName) throws Exception {
		return ServerManager.INSTANCE.getServer(serverName);
	}

	@Override
	protected void doStart() throws Exception {
		super.doStart();
		ServerManager.INSTANCE.incrementActiveComponentInstances();
	}
	
	@Override
	protected void doStop() throws Exception {
		ServerManager.INSTANCE.decrementActiveComponentInstances();
		super.doStop();
	}
}
