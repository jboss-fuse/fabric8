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

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.camel.Exchange;
import org.fusesource.camel.component.sap.util.ComponentDestinationDataProvider;
import org.fusesource.camel.component.sap.util.ComponentServerDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for populating SAP Camel component Exchange properties into
 * Exchange.
 * 
 * @author William Collins <punkhornsw@gmail.com>
 *
 */
public class SapExchangePropertiesUtil {

	private static final transient Logger LOG = LoggerFactory.getLogger(SapExchangePropertiesUtil.class);

	// To prevent instantiation.
	private SapExchangePropertiesUtil() {
	}

	/**
	 * Adds the destination properties of the endpoint to the map stored in the exchange property 'CamelSap.destinationPropertiesMap'.
	 * 
	 * @param endpoint - the endpoint whose destination properties are added to the map.
	 * @param exchange - the exchange containing the map property. 
	 */
	public static void addDestinationPropertiesToExchange(SapRfcDestinationEndpoint endpoint, Exchange exchange) {
		if (endpoint == null || exchange == null) {
			LOG.debug("addDestinationPropertiesToExchange called with null argument(s): endpoint = " + endpoint
					+ ", exchange = " + exchange);
			return;
		}
		String destinationName = endpoint.getDestinationName();
		addDestinationPropertiesToExchange(destinationName, exchange);
	}
	
	/**
	 * Adds the destination properties of the endpoint to the map stored in the exchange property 'CamelSap.destinationPropertiesMap'.
	 * 
	 * @param endpoint - the endpoint whose destination properties are added to the map.
	 * @param exchange - the exchange containing the map property. 
	 */
	public static void addDestinationPropertiesToExchange(SapIDocDestinationEndpoint endpoint, Exchange exchange) {
		if (endpoint == null || exchange == null) {
			LOG.debug("addDestinationPropertiesToExchange called with null argument(s): endpoint = " + endpoint
					+ ", exchange = " + exchange);
			return;
		}
		String destinationName = endpoint.getDestinationName();
		addDestinationPropertiesToExchange(destinationName, exchange);
	}
	
	/**
	 * Adds the destination properties of the endpoint to the map stored in the exchange property 'CamelSap.destinationPropertiesMap'.
	 * 
	 * @param destinationNme - the name of destination whose destination properties are added to the map.
	 * @param exchange - the exchange containing the map property. 
	 */
	public static void addDestinationPropertiesToExchange(String destinationName, Exchange exchange) {
		if (destinationName == null) {
			LOG.warn("addDestinationPropertiesToExchange: endpoint had no destination name");
			return;
		}

		// Get exchange destinations.
		@SuppressWarnings("unchecked")
		Map<String, Properties> destinations = exchange
				.getProperty(SapConstants.SAP_DESTINATION_PROPERTIES_MAP_EXCHANGE_PROPERTY, Map.class);
		if (destinations == null) { // create and add destination properties map
									// to exchange if necessary
			destinations = new HashMap<String, Properties>();
			exchange.setProperty(SapConstants.SAP_DESTINATION_PROPERTIES_MAP_EXCHANGE_PROPERTY, destinations);
		}

		if (destinations.get(destinationName) == null) { // add destination's
															// properties only
															// once
			Properties destinationProperties = ComponentDestinationDataProvider.INSTANCE
					.getDestinationProperties(destinationName);
			if (destinationProperties != null) {
				destinations.put(destinationName, destinationProperties);
				LOG.debug("addDestinationPropertiesToExchange: added properties for destination '" + destinationName
						+ "' to exchange");
			} else {
				LOG.warn("addDestinationPropertiesToExchange: properties for destination '" + destinationName
						+ "' not found");
			}
		}

	}

	/**
	 * Adds the server properties of the endpoint to the map stored in the exchange property 'CamelSap.serverPropertiesMap'.
	 * 
	 * @param endpoint - the endpoint whose server properties are added to the map.
	 * @param exchange - the exchange containing the map property. 
	 */
	public static void addServerPropertiesToExchange(SapRfcServerEndpoint endpoint, Exchange exchange) {
		if (endpoint == null || exchange == null) {
			LOG.debug("addServerPropertiesToExchange called with null argument(s): endpoint = " + endpoint
					+ ", exchange = " + exchange);
			return;
		}
		String serverName = endpoint.getServerName();
		addServerPropertiesToExchange(serverName, exchange);
	}
	
	/**
	 * Adds the server properties of the endpoint to the map stored in the exchange property 'CamelSap.serverPropertiesMap'.
	 * 
	 * @param endpoint - the endpoint whose server properties are added to the map.
	 * @param exchange - the exchange containing the map property. 
	 */
	public static void addServerPropertiesToExchange(SapTransactionalIDocListServerEndpoint endpoint, Exchange exchange) {
		if (endpoint == null || exchange == null) {
			LOG.debug("addServerPropertiesToExchange called with null argument(s): endpoint = " + endpoint
					+ ", exchange = " + exchange);
			return;
		}
		String serverName = endpoint.getServerName();
		addServerPropertiesToExchange(serverName, exchange);
	}
	
	/**
	 * Adds the server properties of the endpoint to the map stored in the exchange property 'CamelSap.serverPropertiesMap'.
	 * 
	 * @param endpoint - the endpoint whose server properties are added to the map.
	 * @param exchange - the exchange containing the map property. 
	 */
	public static void addServerPropertiesToExchange(String serverName, Exchange exchange) {
		if (serverName == null) {
			LOG.warn("addServerPropertiesToExchange: endpoint had no server name");
			return;
		}

		// Get exchange servers.
		@SuppressWarnings("unchecked")
		Map<String, Properties> servers = exchange.getProperty(SapConstants.SAP_SERVER_PROPERTIES_MAP_EXCHANGE_PROPERTY,
				Map.class);
		if (servers == null) { // create and add server properties map to
								// exchange if necessary
			servers = new HashMap<String, Properties>();
			exchange.setProperty(SapConstants.SAP_SERVER_PROPERTIES_MAP_EXCHANGE_PROPERTY, servers);
		}

		if (servers.get(serverName) == null) { // add server's properties only
												// once
			Properties serverProperties = ComponentServerDataProvider.INSTANCE.getServerProperties(serverName);
			if (serverProperties != null) {
				servers.put(serverName, serverProperties);
				LOG.debug(
						"addServerPropertiesToExchange: added properties for server '" + serverName + "' to exchange");
			} else {
				LOG.warn("addServerPropertiesToExchange: properties for server '" + serverName + "' not found");
			}
		}

	}

}
