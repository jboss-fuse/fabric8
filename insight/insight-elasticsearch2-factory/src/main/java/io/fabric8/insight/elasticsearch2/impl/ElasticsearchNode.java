
/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.insight.elasticsearch2.impl;

import org.apache.felix.scr.annotations.*;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Component(name = "io.fabric8.elasticsearch2", policy = ConfigurationPolicy.REQUIRE, immediate = true, configurationFactory = true, metatype = false)
public class ElasticsearchNode {

    private Node nodeDelegate;
    
    private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchNode.class);
    
    public static String CLUSTER_NAME = "cluster.name";
    public static String HTTP_ENABLED = "http.enabled";
    public static String PATH_DATA = "path.data";
    public static String PATH_HOME = "path.home";
    public static String NETWORK_HOST = "network.host";
    public static String CLUSTER_ROUTING_SCHEDULE = "cluster.routing.schedule";
    public static String PATH_PLUGINS = "path.plugins";
    public static String HTTP_CORS_ENABLED = "http.cors.enabled";
    public static String HTTP_CORS_ALLOW_ORIGIN = "http.cors.allow-origin";
    public static String INDEX_MAX_RESULT_WINDOW = "index.max_result_window";
    public static String CONTAINER_DATA_FOLDER = "data";

    @Activate
    protected void activate(final Map<String, Object> props) throws Exception {
        Map<String,String> stringProps = new HashMap<String,String>();
        for (Map.Entry<String, Object> entry : props.entrySet()) {
        	System.out.println(entry.getKey());
            stringProps.put(entry.getKey(), entry.getValue().toString());
        }

        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        
        Settings.Builder settingsBuilder = Settings.settingsBuilder();

       	settingsBuilder.put(CLUSTER_NAME, stringProps.get(CLUSTER_NAME));
       	settingsBuilder.put(HTTP_ENABLED, true);
       	settingsBuilder.put(PATH_DATA, "." + File.separator + CONTAINER_DATA_FOLDER + File.separator + stringProps.get(PATH_DATA));
        settingsBuilder.put(PATH_HOME, "." + File.separator + CONTAINER_DATA_FOLDER + File.separator + stringProps.get(PATH_HOME));
       	settingsBuilder.put(NETWORK_HOST,  stringProps.get(NETWORK_HOST));
       	settingsBuilder.put(CLUSTER_ROUTING_SCHEDULE, stringProps.get(CLUSTER_ROUTING_SCHEDULE));
       	settingsBuilder.put(HTTP_CORS_ENABLED, stringProps.get(HTTP_CORS_ENABLED));
       	settingsBuilder.put(HTTP_CORS_ALLOW_ORIGIN, stringProps.get(HTTP_CORS_ALLOW_ORIGIN));
        settingsBuilder.put(INDEX_MAX_RESULT_WINDOW, stringProps.get(INDEX_MAX_RESULT_WINDOW));
        
        LOG.debug("Creating the elasticsearch node");
        nodeDelegate = NodeBuilder.nodeBuilder().settings(settingsBuilder).build();

        LOG.info("Elasticsearch node created");
        if (nodeDelegate != null) {
        	nodeDelegate.start();
        }
    }

    @Modified
    protected void modified(final Map<String, Object> props) throws Exception {
        deactivate();
        activate(props);
    }

    @Deactivate
    protected void deactivate() {
        if (nodeDelegate != null && !nodeDelegate.isClosed()) {
            nodeDelegate.close();
        }
    }
    
    public Node getDelegateNode() {
    	return nodeDelegate;
    }
}
