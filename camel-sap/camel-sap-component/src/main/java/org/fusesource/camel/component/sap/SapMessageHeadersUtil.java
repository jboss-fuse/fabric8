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

import org.apache.camel.Message;

/**
 * Utility class for populating SAP Camel component message headers into
 * message.
 * 
 * @author William Collins <punkhornsw@gmail.com>
 *
 */
public class SapMessageHeadersUtil {

	// To prevent instantiation.
	private SapMessageHeadersUtil() {
	}

	/**
	 * Add endpoint parameters to SAP Camel component message headers.
	 * 
	 * @param endpoint
	 *            - the endpoint whose parameters are added.
	 * @param message
	 *            - the message added to.
	 */
	public static void addSapHeadersToMessage(SapSynchronousRfcDestinationEndpoint endpoint, Message message) {
		message.removeHeaders(SapConstants.PROPERTY_PREFIX
				+ "*"); /* Remove any previous SAP headers */
		message.setHeader(SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER, SapConstants.SAP_SYNCHRONOUS_RFC_DESTINATION);
		message.setHeader(SapConstants.SAP_DESTINATION_NAME_MESSAGE_HEADER, endpoint.getDestinationName());
		message.setHeader(SapConstants.SAP_RFC_NAME_MESSAGE_HEADER, endpoint.getRfcName());
	}

	/**
	 * Add endpoint parameters to SAP Camel component message headers.
	 * 
	 * @param endpoint
	 *            - the endpoint whose parameters are added.
	 * @param message
	 *            - the message added to.
	 */
	public static void addSapHeadersToMessage(SapTransactionalRfcDestinationEndpoint endpoint, Message message) {
		message.removeHeaders(SapConstants.PROPERTY_PREFIX
				+ "*"); /* Remove any previous SAP headers */
		message.setHeader(SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER, SapConstants.SAP_TRANSACTIONAL_RFC_DESTINATION);
		message.setHeader(SapConstants.SAP_DESTINATION_NAME_MESSAGE_HEADER, endpoint.getDestinationName());
		message.setHeader(SapConstants.SAP_RFC_NAME_MESSAGE_HEADER, endpoint.getRfcName());
	}

	/**
	 * Add endpoint parameters to SAP Camel component message headers.
	 * 
	 * @param endpoint
	 *            - the endpoint whose parameters are added.
	 * @param message
	 *            - the message added to.
	 */
	public static void addSapHeadersToMessage(SapQueuedRfcDestinationEndpoint endpoint, Message message) {
		message.removeHeaders(SapConstants.PROPERTY_PREFIX
				+ "*"); /* Remove any previous SAP headers */
		message.setHeader(SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER, SapConstants.SAP_QUEUED_RFC_DESTINATION);
		message.setHeader(SapConstants.SAP_DESTINATION_NAME_MESSAGE_HEADER, endpoint.getDestinationName());
		message.setHeader(SapConstants.SAP_QUEUE_NAME_MESSAGE_HEADER, endpoint.getQueueName());
		message.setHeader(SapConstants.SAP_RFC_NAME_MESSAGE_HEADER, endpoint.getRfcName());
	}

	/**
	 * Add endpoint parameters to SAP Camel component message headers.
	 * 
	 * @param endpoint
	 *            - the endpoint whose parameters are added.
	 * @param message
	 *            - the message added to.
	 */
	public static void addSapHeadersToMessage(SapSynchronousRfcServerEndpoint endpoint, Message message) {
		message.removeHeaders(SapConstants.PROPERTY_PREFIX
				+ "*"); /* Remove any previous SAP headers */
		message.setHeader(SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER, SapConstants.SAP_SYNCHRONOUS_RFC_SERVER);
		message.setHeader(SapConstants.SAP_SERVER_NAME_MESSAGE_HEADER, endpoint.getServerName());
		message.setHeader(SapConstants.SAP_RFC_NAME_MESSAGE_HEADER, endpoint.getRfcName());
	}

	/**
	 * Add endpoint parameters to SAP Camel component message headers.
	 * 
	 * @param endpoint
	 *            - the endpoint whose parameters are added.
	 * @param message
	 *            - the message added to.
	 */
	public static void addSapHeadersToMessage(SapTransactionalRfcServerEndpoint endpoint, Message message) {
		message.removeHeaders(SapConstants.PROPERTY_PREFIX
				+ "*"); /* Remove any previous SAP headers */
		message.setHeader(SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER, SapConstants.SAP_TRANSACTIONAL_RFC_SERVER);
		message.setHeader(SapConstants.SAP_SERVER_NAME_MESSAGE_HEADER, endpoint.getServerName());
		message.setHeader(SapConstants.SAP_RFC_NAME_MESSAGE_HEADER, endpoint.getRfcName());
	}

	/**
	 * Add endpoint parameters to SAP Camel component message headers.
	 * 
	 * @param endpoint
	 *            - the endpoint whose parameters are added.
	 * @param message
	 *            - the message added to.
	 */
	public static void addSapHeadersToMessage(SapTransactionalIDocDestinationEndpoint endpoint, Message message) {
		message.removeHeaders(SapConstants.PROPERTY_PREFIX
				+ "*"); /* Remove any previous SAP headers */
		message.setHeader(SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER, SapConstants.SAP_IDOC_DESTINATION);
		message.setHeader(SapConstants.SAP_DESTINATION_NAME_MESSAGE_HEADER, endpoint.getDestinationName());
		message.setHeader(SapConstants.SAP_IDOC_TYPE_NAME_MESSAGE_HEADER, endpoint.getIdocType());
		message.setHeader(SapConstants.SAP_IDOC_TYPE_EXTENSION_NAME_MESSAGE_HEADER, endpoint.getIdocTypeExtension());
		message.setHeader(SapConstants.SAP_SYSTEM_RELEASE_NAME_MESSAGE_HEADER, endpoint.getSystemRelease());
		message.setHeader(SapConstants.SAP_APPLICATION_RELEASE_NAME_MESSAGE_HEADER, endpoint.getApplicationRelease());
	}

	/**
	 * Add endpoint parameters to SAP Camel component message headers.
	 * 
	 * @param endpoint
	 *            - the endpoint whose parameters are added.
	 * @param message
	 *            - the message added to.
	 */
	public static void addSapHeadersToMessage(SapTransactionalIDocListDestinationEndpoint endpoint, Message message) {
		message.removeHeaders(SapConstants.PROPERTY_PREFIX
				+ "*"); /* Remove any previous SAP headers */
		message.setHeader(SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER, SapConstants.SAP_IDOC_LIST_DESTINATION);
		message.setHeader(SapConstants.SAP_DESTINATION_NAME_MESSAGE_HEADER, endpoint.getDestinationName());
		message.setHeader(SapConstants.SAP_IDOC_TYPE_NAME_MESSAGE_HEADER, endpoint.getIdocType());
		message.setHeader(SapConstants.SAP_IDOC_TYPE_EXTENSION_NAME_MESSAGE_HEADER, endpoint.getIdocTypeExtension());
		message.setHeader(SapConstants.SAP_SYSTEM_RELEASE_NAME_MESSAGE_HEADER, endpoint.getSystemRelease());
		message.setHeader(SapConstants.SAP_APPLICATION_RELEASE_NAME_MESSAGE_HEADER, endpoint.getApplicationRelease());
	}

	/**
	 * Add endpoint parameters to SAP Camel component message headers.
	 * 
	 * @param endpoint
	 *            - the endpoint whose parameters are added.
	 * @param message
	 *            - the message added to.
	 */
	public static void addSapHeadersToMessage(SapQueuedIDocDestinationEndpoint endpoint, Message message) {
		message.removeHeaders(SapConstants.PROPERTY_PREFIX
				+ "*"); /* Remove any previous SAP headers */
		message.setHeader(SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER, SapConstants.SAP_QUEUED_IDOC_DESTINATION);
		message.setHeader(SapConstants.SAP_DESTINATION_NAME_MESSAGE_HEADER, endpoint.getDestinationName());
		message.setHeader(SapConstants.SAP_IDOC_TYPE_NAME_MESSAGE_HEADER, endpoint.getIdocType());
		message.setHeader(SapConstants.SAP_IDOC_TYPE_EXTENSION_NAME_MESSAGE_HEADER, endpoint.getIdocTypeExtension());
		message.setHeader(SapConstants.SAP_SYSTEM_RELEASE_NAME_MESSAGE_HEADER, endpoint.getSystemRelease());
		message.setHeader(SapConstants.SAP_APPLICATION_RELEASE_NAME_MESSAGE_HEADER, endpoint.getApplicationRelease());
	}

	/**
	 * Add endpoint parameters to SAP Camel component message headers.
	 * 
	 * @param endpoint
	 *            - the endpoint whose parameters are added.
	 * @param message
	 *            - the message added to.
	 */
	public static void addSapHeadersToMessage(SapQueuedIDocListDestinationEndpoint endpoint, Message message) {
		message.removeHeaders(SapConstants.PROPERTY_PREFIX
				+ "*"); /* Remove any previous SAP headers */
		message.setHeader(SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER, SapConstants.SAP_QUEUED_IDOC_LIST_DESTINATION);
		message.setHeader(SapConstants.SAP_DESTINATION_NAME_MESSAGE_HEADER, endpoint.getDestinationName());
		message.setHeader(SapConstants.SAP_IDOC_TYPE_NAME_MESSAGE_HEADER, endpoint.getIdocType());
		message.setHeader(SapConstants.SAP_IDOC_TYPE_EXTENSION_NAME_MESSAGE_HEADER, endpoint.getIdocTypeExtension());
		message.setHeader(SapConstants.SAP_SYSTEM_RELEASE_NAME_MESSAGE_HEADER, endpoint.getSystemRelease());
		message.setHeader(SapConstants.SAP_APPLICATION_RELEASE_NAME_MESSAGE_HEADER, endpoint.getApplicationRelease());
	}

	/**
	 * Add endpoint parameters to SAP Camel component message headers.
	 * 
	 * @param endpoint
	 *            - the endpoint whose parameters are added.
	 * @param message
	 *            - the message added to.
	 */
	public static void addSapHeadersToMessage(SapTransactionalIDocListServerEndpoint endpoint, Message message) {
		message.removeHeaders(SapConstants.PROPERTY_PREFIX
				+ "*"); /* Remove any previous SAP headers */
		message.setHeader(SapConstants.SAP_SCHEME_NAME_MESSAGE_HEADER, SapConstants.SAP_IDOC_LIST_SERVER);
		message.setHeader(SapConstants.SAP_SERVER_NAME_MESSAGE_HEADER, endpoint.getServerName());
		message.setHeader(SapConstants.SAP_IDOC_TYPE_NAME_MESSAGE_HEADER, endpoint.getIdocType());
		message.setHeader(SapConstants.SAP_IDOC_TYPE_EXTENSION_NAME_MESSAGE_HEADER, endpoint.getIdocTypeExtension());
		message.setHeader(SapConstants.SAP_SYSTEM_RELEASE_NAME_MESSAGE_HEADER, endpoint.getSystemRelease());
		message.setHeader(SapConstants.SAP_APPLICATION_RELEASE_NAME_MESSAGE_HEADER, endpoint.getApplicationRelease());
	}
}
