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
package io.fabric8.insight.elasticsearch.auth.plugin.auth.integration;

import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.rest.client.http.HttpGetWithEntity;
import org.elasticsearch.test.rest.client.http.HttpRequestBuilder;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;

public class AbstractElasticsearchAuthPluginTest extends ElasticsearchIntegrationTest {
    public static final String DEFAULT_DATA_DIRECTORY = System.getProperty("buildDirectory")+ File.separator+"elasticsearch-data";
    public static final String PROTOCOL = "http";
    public static final String HOST = "localhost";
    public static final int PORT = 9200;
    public static final String STATUS_PATH = "/_status";

    protected static HttpRequestBuilder httpClient() {
        return new HttpRequestBuilder(HttpClients.createDefault()).host(HOST).port(PORT);
    }

    protected static HttpUriRequest httpRequest() {
        HttpUriRequest httpUriRequest = null;
        try {
            httpUriRequest = new HttpGetWithEntity(new URI(PROTOCOL, null, HOST, PORT, STATUS_PATH, null, null));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        return httpUriRequest;
    }

    protected static CloseableHttpClient closeableHttpClient() {
        return HttpClients.createDefault();
    }
}
