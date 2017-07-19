/**
 * Copyright 2017 Red Hat, Inc.
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

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.fusesource.camel.component.sap.model.rfc.RepositoryData;
import org.fusesource.camel.component.sap.util.ComponentRepositoryDataProvider;
import org.fusesource.camel.component.sap.util.RfcUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sap.conn.idoc.jco.JCoIDoc;
import com.sap.conn.idoc.jco.JCoIDocServer;
import com.sap.conn.jco.JCoCustomRepository;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.server.JCoServerState;

public enum ServerManager {
	INSTANCE;
	
	private static final Logger LOG = LoggerFactory.getLogger(ServerManager.class);
	
	/* Interval to wait while JCo server is stopping */
	private static final long JCO_SERVER_STOPPING_WAIT_INTERVAL = 100;
	
	protected static final AtomicLong activeComponentInstances = new AtomicLong();

	protected static final Map<String, JCoIDocServer> activeServers = new HashMap<String, JCoIDocServer>();

	protected static final File tidStoresLocation = new File(".");

	protected static final Map<String, JCoCustomRepository> repositories = new HashMap<String, JCoCustomRepository>();

	protected static final ServerErrorAndExceptionListener serverErrorAndExceptionListener = new ServerErrorAndExceptionListener();

	protected static final ServerStateChangedListener serverStateChangedListener = new ServerStateChangedListener();

	public synchronized void incrementActiveComponentInstances() {
		activeComponentInstances.incrementAndGet();
	}
	
	public synchronized void decrementActiveComponentInstances() {
		if (activeComponentInstances.decrementAndGet() == 0) {
			for (JCoIDocServer server : activeServers.values()) {
				server.stop();
				server.removeServerErrorListener(serverErrorAndExceptionListener);
				server.removeServerExceptionListener(serverErrorAndExceptionListener);
				server.removeServerStateChangedListener(serverStateChangedListener);
				server.setCallHandlerFactory(null);
				server.setIDocHandlerFactory(null);
				server.setTIDHandler(null);
				server.release();
			}
			activeServers.clear();
		}
	}
	
	public synchronized JCoIDocServer getServer(String serverName) throws Exception {
		JCoIDocServer server = activeServers.get(serverName);

		if (server == null) {
			//
			// Create new JCo Server instance.
			//

			server = JCoIDoc.getServer(serverName);

			if (server.getState() == JCoServerState.STARTED || server.getState() == JCoServerState.ALIVE) {
				// Another application has already registered and started
				// this server connection.
				throw new Exception("The server connection '" + serverName + "' is already in use");
			}

			if (server.getState() == JCoServerState.STOPPING) {
				// Wait for server to stop
				while (server.getState() != JCoServerState.STOPPED) {
					wait(JCO_SERVER_STOPPING_WAIT_INTERVAL);
				}
			}

			// Add handlers and listeners to server
			server.setCallHandlerFactory(new FunctionHandlerFactory());
			server.setIDocHandlerFactory(new IDocHandlerFactory());
			server.setTIDHandler(new ServerTIDHandler(tidStoresLocation, serverName));
			server.addServerExceptionListener(serverErrorAndExceptionListener);
			server.addServerErrorListener(serverErrorAndExceptionListener);
			server.addServerStateChangedListener(serverStateChangedListener);

			String repositoryDestinationName = server.getRepositoryDestination();
			JCoDestination repositoryDestination = null;
			try {
				repositoryDestination = JCoDestinationManager.getDestination(repositoryDestinationName);
			} catch (Exception e1) {
				LOG.warn("Unable to get repository destination'" + repositoryDestinationName + "' for server '"
						+ serverName + "'", e1);
			}

			// Set up custom repository for inbound RFCs.
			JCoCustomRepository repository = getRepository(serverName);
			if (repository != null) {
				if (repositoryDestination != null) {
					try {
						repository.setDestination(repositoryDestination);
					} catch (Exception e) {
						LOG.warn("Unable to set destination on custom repository for server '" + serverName + "'", e);
					}
				}
				server.setRepository(repository);
			}

			activeServers.put(serverName, server);

			server.start();
			LOG.debug("Started server " + server.getProgramID());
		}
		return server;
	}

	public synchronized JCoCustomRepository getRepository(String serverName) {
		JCoCustomRepository repository = repositories.get(serverName);
		if (repository == null) {
			RepositoryData repositoryData = ComponentRepositoryDataProvider.INSTANCE.getRepositoryData(serverName);
			repository = RfcUtil.createRepository(serverName, repositoryData);
			repositories.put(serverName, repository);
		}
		return repository;
	}

}
