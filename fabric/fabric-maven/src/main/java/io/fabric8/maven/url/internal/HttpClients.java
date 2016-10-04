/*
 * Copyright (C) 2014 Guillaume Nodet
 *
 * Licensed  under the  Apache License,  Version 2.0  (the "License");
 * you may not use  this file  except in  compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed  under the  License is distributed on an "AS IS" BASIS,
 * WITHOUT  WARRANTIES OR CONDITIONS  OF ANY KIND, either  express  or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.maven.url.internal;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import io.fabric8.maven.url.ServiceConstants;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLInitializationException;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.maven.wagon.providers.http.RelaxedTrustStrategy;
import org.ops4j.util.property.PropertyResolver;

public class HttpClients {

    public static CloseableHttpClient createClient(PropertyResolver resolver, String pid) {
        return HttpClientBuilder.create() //
                .useSystemProperties() //
                .disableConnectionState() //
                .setConnectionManager(createConnManager(resolver, pid)) //
                .setRetryHandler(createRetryHandler(resolver, pid))
                .build();
    }

    private static PoolingHttpClientConnectionManager createConnManager(PropertyResolver resolver, String pid) {
        boolean SSL_INSECURE = getBoolean(resolver, "maven.wagon.http.ssl.insecure",
                !getBoolean(resolver, pid + "certificateCheck", false));
        boolean IGNORE_SSL_VALIDITY_DATES = getBoolean(resolver, "maven.wagon.http.ssl.ignore.validity.dates", false);
        boolean SSL_ALLOW_ALL = getBoolean(resolver, "maven.wagon.http.ssl.allowall",
                !getBoolean(resolver, pid + "certificateCheck", false));
        boolean PERSISTENT_POOL = getBoolean(resolver, "maven.wagon.http.pool", true);
        int MAX_CONN_PER_ROUTE = getInteger(resolver, "maven.wagon.httpconnectionManager.maxPerRoute", 20);
        int MAX_CONN_TOTAL = getInteger(resolver, "maven.wagon.httpconnectionManager.maxTotal", 40);

        String sslProtocolsStr = getProperty(resolver, "https.protocols", null);
        String cipherSuitesStr = getProperty(resolver, "https.cipherSuites", null);
        String[] sslProtocols = sslProtocolsStr != null ? sslProtocolsStr.split(" *, *") : null;
        String[] cipherSuites = cipherSuitesStr != null ? cipherSuitesStr.split(" *, *") : null;

        SSLConnectionSocketFactory sslConnectionSocketFactory;
        if (SSL_INSECURE) {
            try {
                SSLContext sslContext = new SSLContextBuilder().loadTrustMaterial(null,
                        new RelaxedTrustStrategy(
                                IGNORE_SSL_VALIDITY_DATES)).build();
                sslConnectionSocketFactory = new SSLConnectionSocketFactory(sslContext, sslProtocols, cipherSuites,
                        SSL_ALLOW_ALL
                                ? NoopHostnameVerifier.INSTANCE
                                : new DefaultHostnameVerifier());
            } catch (Exception ex) {
                throw new SSLInitializationException(ex.getMessage(), ex);
            }
        } else {
            sslConnectionSocketFactory =
                    new SSLConnectionSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), sslProtocols,
                            cipherSuites,
                            new DefaultHostnameVerifier());
        }

        Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create().register("http",
                PlainConnectionSocketFactory.INSTANCE).register(
                "https", sslConnectionSocketFactory).build();

        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager(registry);
        if (PERSISTENT_POOL) {
            connManager.setDefaultMaxPerRoute(MAX_CONN_PER_ROUTE);
            connManager.setMaxTotal(MAX_CONN_TOTAL);
        } else {
            connManager.setMaxTotal(1);
        }

        boolean soKeepAlive = getBoolean(resolver, pid + ServiceConstants.PROPERTY_SOCKET_SO_KEEPALIVE, false);
        int soLinger = getInteger(resolver, pid + ServiceConstants.PROPERTY_SOCKET_SO_LINGER, -1);
        boolean soReuseAddress = getBoolean(resolver, pid + ServiceConstants.PROPERTY_SOCKET_SO_REUSEADDRESS, false);
        boolean soTcpNoDelay = getBoolean(resolver, pid + ServiceConstants.PROPERTY_SOCKET_TCP_NODELAY, true);
//        int soTimeout = getInteger( resolver, pid + ServiceConstants.PROPERTY_SOCKET_SO_TIMEOUT, 0 );
        SocketConfig socketConfig = SocketConfig.custom()
                .setSoKeepAlive(soKeepAlive) // default false
                .setSoLinger(soLinger) // default -1
                .setSoReuseAddress(soReuseAddress) // default false
                .setTcpNoDelay(soTcpNoDelay) // default true
                .setSoTimeout(0) // default 0, but set in org.apache.http.impl.conn.CPoolProxy.setSocketTimeout()
                // this value is not used
                .build();
        connManager.setDefaultSocketConfig(socketConfig);

        int bufferSize = getInteger(resolver, pid + ServiceConstants.PROPERTY_CONNECTION_BUFFER_SIZE, 8192);
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setBufferSize(bufferSize) // default 8192
                .setFragmentSizeHint(bufferSize) // default 'buffer size'
                .build();
        connManager.setDefaultConnectionConfig(connectionConfig);

        return connManager;
    }

    private static HttpRequestRetryHandler createRetryHandler(PropertyResolver resolver, String pid) {
        int retryCount = getInteger(resolver, pid + ServiceConstants.PROPERTY_CONNECTION_RETRY_COUNT, 3);
        return new DefaultHttpRequestRetryHandler(retryCount, false);
    }

    private static int getInteger(PropertyResolver resolver, String key, int def) {
        return Integer.parseInt(getProperty(resolver, key, Integer.toString(def)));
    }

    private static boolean getBoolean(PropertyResolver resolver, String key, boolean def) {
        return Boolean.parseBoolean(getProperty(resolver, key, Boolean.toString(def)));
    }

    private static String getProperty(PropertyResolver resolver, String key, String def) {
        String val = resolver != null ? resolver.get(key) : System.getProperty(key);
        return (val == null) ? def : val;
    }

}
