/*
 * Copyright (C) 2014 Guillaume Nodet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.maven.url.internal.wagon;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import io.fabric8.common.util.URLUtils;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.auth.AUTH;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HTTP;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.providers.http.AbstractHttpClientWagon;
import org.apache.maven.wagon.providers.http.HttpMethodConfiguration;
import org.apache.maven.wagon.providers.http.HttpWagon;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.proxy.ProxyInfoProvider;
import org.apache.maven.wagon.repository.Repository;

/**
 * An http wagon provider providing more configuration options
 * through the use of an HttpClient instance.
 *
 * @author Guillaume Nodet
 */
public class ConfigurableHttpWagon extends HttpWagon {

    private final CloseableHttpClient client;

    public ConfigurableHttpWagon(CloseableHttpClient client, int readTimeout, int connectionTimeout) {
        this.client = client;
        setReadTimeout(readTimeout);
        setTimeout(connectionTimeout);
    }

    @Override
    protected CloseableHttpResponse execute(HttpUriRequest httpMethod) throws HttpException, IOException {
        setHeaders(httpMethod);
        String userAgent = getUserAgent(httpMethod);
        if (userAgent != null) {
            httpMethod.setHeader(HTTP.USER_AGENT, userAgent);
        }

        RequestConfig.Builder requestConfigBuilder = RequestConfig.custom();
        // WAGON-273: default the cookie-policy to browser compatible
        requestConfigBuilder.setCookieSpec(CookieSpecs.BROWSER_COMPATIBILITY);

        Repository repo = getRepository();
        ProxyInfo proxyInfo = getProxyInfo(repo.getProtocol(), repo.getHost());
        if (proxyInfo != null) {
            HttpHost proxy = new HttpHost(proxyInfo.getHost(), proxyInfo.getPort());
            requestConfigBuilder.setProxy(proxy);
        }

        HttpMethodConfiguration config =
                getHttpConfiguration() == null ? null : getHttpConfiguration().getMethodConfiguration(httpMethod);

        if (config != null) {
            copyConfig(config, requestConfigBuilder);
        } else {
            requestConfigBuilder.setSocketTimeout(getReadTimeout());
            requestConfigBuilder.setConnectTimeout(getTimeout());
        }

        getLocalContext().setRequestConfig(requestConfigBuilder.build());

        if (config != null && config.isUsePreemptive()) {
            HttpHost targetHost = new HttpHost(repo.getHost(), repo.getPort(), repo.getProtocol());
            AuthScope targetScope = getBasicAuthScope().getScope(targetHost);

            if (getCredentialsProvider().getCredentials(targetScope) != null) {
                BasicScheme targetAuth = new BasicScheme();
                targetAuth.processChallenge(new BasicHeader(AUTH.WWW_AUTH, "BASIC preemptive"));
                getAuthCache().put(targetHost, targetAuth);
            }
        }

        if (proxyInfo != null) {
            if (proxyInfo.getHost() != null) {
                HttpHost proxyHost = new HttpHost(proxyInfo.getHost(), proxyInfo.getPort());
                AuthScope proxyScope = getProxyBasicAuthScope().getScope(proxyHost);

                String proxyUsername = proxyInfo.getUserName();
                String proxyPassword = proxyInfo.getPassword();
                String proxyNtlmHost = proxyInfo.getNtlmHost();
                String proxyNtlmDomain = proxyInfo.getNtlmDomain();

                if (proxyUsername != null && proxyPassword != null) {
                    Credentials creds;
                    if (proxyNtlmHost != null || proxyNtlmDomain != null) {
                        creds = new NTCredentials(proxyUsername, proxyPassword, proxyNtlmHost, proxyNtlmDomain);
                    } else {
                        creds = new UsernamePasswordCredentials(proxyUsername, proxyPassword);
                    }

                    getCredentialsProvider().setCredentials(proxyScope, creds);
                    BasicScheme proxyAuth = new BasicScheme();
                    proxyAuth.processChallenge(new BasicHeader(AUTH.PROXY_AUTH, "BASIC preemptive"));
                    getAuthCache().put(proxyHost, proxyAuth);
                }
            }
        }

        return client.execute(httpMethod, getLocalContext());
    }

    @Override
    public void connect(Repository repository, AuthenticationInfo authenticationInfo, ProxyInfoProvider proxyInfoProvider)
            throws ConnectionException, AuthenticationException {
        if (repository == null) {
            throw new IllegalStateException("The repository specified cannot be null.");
        }

        if (authenticationInfo == null) {
            authenticationInfo = new AuthenticationInfo();
        }

        if (authenticationInfo.getUserName() == null) {
            // Get user/pass that were encoded in the URL.
            if (repository.getUsername() != null) {
                // Need to decode username/password because it may contain encoded characters (http://www.w3schools.com/tags/ref_urlencode.asp)
                // A common encoding is to provide a username as an email address like user%40domain.org
                authenticationInfo.setUserName(URLUtils.decode(repository.getUsername()));
                if (repository.getPassword() != null && authenticationInfo.getPassword() == null) {
                    authenticationInfo.setPassword(URLUtils.decode(repository.getPassword()));
                }
            }
        }

        super.connect(repository, authenticationInfo, proxyInfoProvider);
    }

    private AuthCache getAuthCache() {
        return getField(AuthCache.class, "authCache");
    }

    private CredentialsProvider getCredentialsProvider() {
        return getField(CredentialsProvider.class, "credentialsProvider");
    }

    private HttpClientContext getLocalContext() {
        return getField(HttpClientContext.class, "localContext");
    }

    private <T> T getField(Class<T> clazz, String name) {
        try {
            Field field = AbstractHttpClientWagon.class.getDeclaredField(name);
            field.setAccessible(true);
            return clazz.cast(field.get(this));
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Unable to retrieve field " + name, e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to retrieve field " + name, e);
        }
    }

    private void copyConfig(HttpMethodConfiguration config, RequestConfig.Builder builder) {
        try {
            Class<?> clazz = getClass().getClassLoader().loadClass("org.apache.maven.wagon.providers.http.ConfigurationUtils");
            Method method = clazz.getMethod("copyConfig", HttpMethodConfiguration.class, RequestConfig.Builder.class);
            method.invoke(null, config, builder);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unable to call copyConfig", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Unable to call copyConfig", e);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to call copyConfig", e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to call copyConfig", e);
        }
    }

}
