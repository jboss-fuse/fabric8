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


import io.fabric8.insight.elasticsearch.auth.plugin.HttpBasicServerPlugin;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import org.elasticsearch.test.ElasticsearchIntegrationTest.Scope;
import org.elasticsearch.test.rest.client.http.HttpResponse;
import org.junit.Test;

@ClusterScope(transportClientRatio = 0.0, scope = Scope.SUITE, numDataNodes = 1)
public class DefaultConfigurationIntegrationTest extends AbstractElasticsearchAuthPluginTest {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.settingsBuilder()
                .put("plugin.types", HttpBasicServerPlugin.class.getName())
                .put("path.data", DEFAULT_DATA_DIRECTORY)
                .build();
    }

    @Test
    public void testHealthCheck() throws Exception {
        HttpResponse response = httpClient().path("/").execute();
        assertEquals(RestStatus.OK.getStatus(), response.getStatusCode());
    }

    @Test
    public void notAuthenticated() throws Exception {
        HttpResponse response = httpClient().path(STATUS_PATH).execute();
        assertEquals(RestStatus.UNAUTHORIZED.getStatus(), response.getStatusCode());
    }

    @Test
    public void isBasicAuthenticated() throws Exception {
        HttpUriRequest request = httpRequest();
        String credentials = "admin:admin";
        request.setHeader("Authorization", "Basic " + Base64.encodeBytes(credentials.getBytes()));
        CloseableHttpResponse response = closeableHttpClient().execute(request);
        assertEquals(RestStatus.OK.getStatus(), response.getStatusLine().getStatusCode());
    }

    @Test
    public void isWronglyBasicAuthenticated() throws Exception {
        HttpUriRequest request = httpRequest();
        String credentials = "admin:12345";
        request.setHeader("Authorization", "Basic " + Base64.encodeBytes(credentials.getBytes()));
        CloseableHttpResponse response = closeableHttpClient().execute(request);
        assertEquals(RestStatus.UNAUTHORIZED.getStatus(), response.getStatusLine().getStatusCode());
    }

    @Test
    public void hasWrongRoleBasicAuthenticated() throws Exception {
        HttpUriRequest request = httpRequest();
        String credentials = "user:admin";
        request.setHeader("Authorization", "Basic " + Base64.encodeBytes(credentials.getBytes()));
        CloseableHttpResponse response = closeableHttpClient().execute(request);
        assertEquals(RestStatus.UNAUTHORIZED.getStatus(), response.getStatusLine().getStatusCode());
    }

}
