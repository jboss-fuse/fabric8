/**
 *  Copyright 2005-2015 Red Hat, Inc.
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
package io.fabric8.gateway.handlers.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.VoidHandler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.http.HttpClientRequest;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.http.HttpHeaders;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.HttpServerResponse;

import io.fabric8.gateway.CallDetailRecord;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Vert.x HTTP Gateway web handler
 */
public class HttpGatewayHandler implements Handler<HttpServerRequest> {
    private static final transient Logger LOG = LoggerFactory.getLogger(HttpGatewayHandler.class);

    private final Vertx vertx;
    private final HttpGateway httpGateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpGatewayHandler(Vertx vertx, HttpGateway httpGateway) {
        this.vertx = vertx;
        this.httpGateway = httpGateway;
    }

    @Override
    public void handle(HttpServerRequest request) {
        long callStart = System.nanoTime();

        LOG.debug("Proxying request: {} {}", request.method(), request.uri());

        // lets map the request URI to map to the service URI and then the renaming URI
        // using mapping rules...
        Map<String, MappedServices> mappingRules = httpGateway.getMappedServices();
        try {
            if (isMappingIndexRequest(request)) {
                // lets return the JSON of all the results
                doReturnIndex(request, mappingRules);
            } else {
                doRouteRequest(mappingRules, request);
            }
            CallDetailRecord cdr = new CallDetailRecord(System.nanoTime() - callStart, null);
            httpGateway.addCallDetailRecord(cdr);
        } catch (Throwable e) {
            LOG.error("Caught: " + e, e);
            CallDetailRecord cdr = new CallDetailRecord(System.nanoTime() - callStart, new Date() + ":" + e.getMessage());
            httpGateway.addCallDetailRecord(cdr);
            request.response().setStatusCode(404);
            StringWriter buffer = new StringWriter();
            e.printStackTrace(new PrintWriter(buffer));
            request.response().setStatusMessage("Error: " + e + "\nStack Trace: " + buffer);
            request.response().close();
        }
    }

    protected void doReturnIndex(HttpServerRequest request, Map<String, MappedServices> mappingRules) throws IOException {
        String json = mappingRulesToJson(mappingRules);
        HttpServerResponse response = request.response();
        response.headers().set("ContentType", "application/json");
        if ("HEAD".equals(request.method())) {
            // For HEAD method you must not return message body but must send 'Content-Length' header
            // https://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html#sec9.4
            String contentLength = String.valueOf(new Buffer(json).length());
            response.putHeader(HttpHeaders.CONTENT_LENGTH, contentLength)
                    .end();
        } else {
            response.end(json);
        }
        response.setStatusCode(200);
    }

    protected String mappingRulesToJson(Map<String, MappedServices> rules) throws IOException {
        Map<String, Collection<String>> data = new HashMap<>();

        Set<Map.Entry<String, MappedServices>> entries = rules.entrySet();
        for (Map.Entry<String, MappedServices> entry : entries) {
            String key = entry.getKey();
            MappedServices value = entry.getValue();
            Collection<String> serviceUrls = value.getServiceUrls();
            data.put(key, serviceUrls);
        }
        return mapper.writeValueAsString(data);
    }

    protected void doRouteRequest(Map<String, MappedServices> mappingRules, final HttpServerRequest request) {
        String uri = request.uri();
        String uri2 = normalizeUri(uri);

        HttpClient client = null;
        String remaining = null;
        String prefix = null;
        String proxyServiceUrl = null;
        String reverseServiceUrl = null;

        MappedServices mappedServices = null;
        URL clientURL = null;
        Set<Map.Entry<String, MappedServices>> entries = mappingRules.entrySet();
        for (Map.Entry<String, MappedServices> entry : entries) {
            String path = entry.getKey();
            mappedServices = entry.getValue();

            String pathPrefix = path;
            boolean uriMatches = uri.startsWith(pathPrefix);
            boolean uri2Matches = uri2 != null && uri2.startsWith(pathPrefix);
            if (uriMatches || uri2Matches) {
                int pathPrefixLength = pathPrefix.length();
                if (uri2Matches && pathPrefixLength < uri2.length()) {
                    remaining = uri2.substring(pathPrefixLength);
                } else if (pathPrefixLength < uri.length()) {
                    remaining = uri.substring(pathPrefixLength);
                } else {
                    remaining = null;
                }

                // now lets pick a service for this path
                proxyServiceUrl = mappedServices.chooseService(request);
                if (proxyServiceUrl != null) {
                    // lets create a client for this request...
                    try {
                        clientURL = new URL(proxyServiceUrl);
                        client = createClient(clientURL);
                        prefix = clientURL.getPath();
                        reverseServiceUrl = request.absoluteURI().resolve(pathPrefix).toString();
                        if (reverseServiceUrl.endsWith("/")) {
                            reverseServiceUrl = reverseServiceUrl.substring(0, reverseServiceUrl.length() - 1);
                        }
                        break;
                    } catch (MalformedURLException e) {
                        LOG.warn("Failed to parse URL: " + proxyServiceUrl + ". " + e, e);
                    }
                }
            }
        }

        if (client != null) {
            String servicePath = prefix != null ? prefix : "";
            // we should usually end the prefix path with a slash for web apps at least
            if (servicePath.length() > 0 && !servicePath.endsWith("/")) {
                servicePath += "/";
            }
            if (remaining != null) {
                servicePath += remaining;
            }

            LOG.info("Proxying request {} to service path: {} on service: {} reverseServiceUrl: {}", uri, servicePath, proxyServiceUrl, reverseServiceUrl);
            final HttpClient finalClient = client;
            Handler<HttpClientResponse> responseHandler = new Handler<HttpClientResponse>() {
                public void handle(HttpClientResponse clientResponse) {
                    LOG.debug("Proxying response: {}", clientResponse.statusCode());
                    request.response().setStatusCode(clientResponse.statusCode());
                    request.response().headers().set(clientResponse.headers());
                    request.response().setChunked(true);
                    clientResponse.dataHandler(new Handler<Buffer>() {
                        public void handle(Buffer data) {
                            LOG.debug("Proxying response body: {}", data);
                            request.response().write(data);
                        }
                    });
                    clientResponse.endHandler(new VoidHandler() {
                        public void handle() {
                            request.response().end();
                            finalClient.close();
                        }
                    });
                }
            };
            if (mappedServices != null) {
                ProxyMappingDetails proxyMappingDetails = new ProxyMappingDetails(proxyServiceUrl, reverseServiceUrl, servicePath);
                responseHandler = mappedServices.wrapResponseHandlerInPolicies(request, responseHandler, proxyMappingDetails);
            }
            final HttpClientRequest clientRequest = client.request(request.method(), servicePath, responseHandler);
            clientRequest.headers().set(request.headers());
            clientRequest.setChunked(true);
            request.dataHandler(new Handler<Buffer>() {
                public void handle(Buffer data) {
                    LOG.debug("Proxying request body: {}", data);
                    clientRequest.write(data);
                }
            });
            request.endHandler(new VoidHandler() {
                public void handle() {
                    LOG.debug("end of the request");
                    clientRequest.end();
                }
            });

        } else {
            //  lets return a 404
            LOG.info("Could not find matching proxy path for {} from paths: {}", uri, mappingRules.keySet());
            request.response().setStatusCode(404);
            request.response().close();
        }
    }

    protected boolean isMappingIndexRequest(HttpServerRequest request) {
        if (httpGateway == null || !httpGateway.isEnableIndex()) {
            return false;
        }
        String uri = request.uri();
        return uri == null || uri.length() == 0 || uri.equals("/");
    }

    protected HttpClient createClient(URL url) throws MalformedURLException {
        // lets create a client
        HttpClient client = vertx.createHttpClient();
        client.setHost(url.getHost());
        client.setPort(url.getPort());
        return client;

    }

    /**
     * Normalizes the passed in URI value by appending a '/' to the path if necessary.
     *
     * @return same URI with the normalized path or <code>null</code> if the path already ends with '/'
     */
    protected static String normalizeUri(String value) {
        try {
            String result = null;
            URI uri = new URI(value);
            String path = uri.getPath();
            if (!path.endsWith("/")) {
                result = value.replaceFirst(Pattern.quote(path), path + "/");
            }
            return result;
        } catch (URISyntaxException e) {
            LOG.debug("Exception caught while normalizing URI path - proceeding with the original path value", e);
            return null;
        }
    }
}
