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

/**
 * SAP Camel Component Constants
 * 
 * @author William Collins <punkhornsw@gmail.com>
 *
 */
public interface SapConstants {

    // Prefix for parameters when passed as exchange or message header properties
    public static final String PROPERTY_PREFIX = "CamelSap.";
    
    //
    // Endpoint Scheme Names
    //
    
    public static final String SAP_SYNCHRONOUS_RFC_DESTINATION = "sap-srfc-destination";
    
    public static final String SAP_TRANSACTIONAL_RFC_DESTINATION = "sap-trfc-destination";
    
    public static final String SAP_QUEUED_RFC_DESTINATION = "sap-qrfc-destination";
    
    public static final String SAP_SYNCHRONOUS_RFC_SERVER = "sap-srfc-server";
    
    public static final String SAP_TRANSACTIONAL_RFC_SERVER = "sap-trfc-server";
    
    
    public static final String SAP_IDOC_DESTINATION = "sap-idoc-destination";
    
    public static final String SAP_IDOC_LIST_DESTINATION = "sap-idoclist-destination";
    
    public static final String SAP_QUEUED_IDOC_DESTINATION = "sap-qidoc-destination";
    
    public static final String SAP_QUEUED_IDOC_LIST_DESTINATION = "sap-qidoclist-destination";
    
    public static final String SAP_IDOC_LIST_SERVER = "sap-idoclist-server";
    
    //
    // Message Header Properties
    //

    public static final String SAP_SCHEME_NAME_MESSAGE_HEADER = PROPERTY_PREFIX + "scheme";

    public static final String SAP_DESTINATION_NAME_MESSAGE_HEADER = PROPERTY_PREFIX + "destinationName";

    public static final String SAP_SERVER_NAME_MESSAGE_HEADER = PROPERTY_PREFIX + "serverName";

    public static final String SAP_QUEUE_NAME_MESSAGE_HEADER = PROPERTY_PREFIX + "queueName";

    public static final String SAP_RFC_NAME_MESSAGE_HEADER = PROPERTY_PREFIX + "rfcName";

    public static final String SAP_IDOC_TYPE_NAME_MESSAGE_HEADER = PROPERTY_PREFIX + "idocType";

    public static final String SAP_IDOC_TYPE_EXTENSION_NAME_MESSAGE_HEADER = PROPERTY_PREFIX + "idocTypeExtension";

    public static final String SAP_SYSTEM_RELEASE_NAME_MESSAGE_HEADER = PROPERTY_PREFIX + "systemRelease";

    public static final String SAP_APPLICATION_RELEASE_NAME_MESSAGE_HEADER = PROPERTY_PREFIX + "applicationRelease";
    
    //
    // Exchange Properties
    //
    
    /**
     * The name of exchange property where the destination properties for each destination access in an exchange is stored. 
     * The object stored here is a map of {@link java.util.Map} of {@link java.util.Properties} keyed by destination name.
     */
    public static final String SAP_DESTINATION_PROPERTIES_MAP_EXCHANGE_PROPERTY = PROPERTY_PREFIX + "destinationPropertiesMap";
    
    /**
     * The name of exchange property where the server properties for each server access in an exchange is stored. 
     * The object stored here is a map of {@link java.util.Map} of {@link java.util.Properties} keyed by server name.
     */
    public static final String SAP_SERVER_PROPERTIES_MAP_EXCHANGE_PROPERTY = PROPERTY_PREFIX + "serverPropertiesMap";
    
}
