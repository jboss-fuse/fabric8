/*
 *  Copyright 2016 Grzegorz Grzybek
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.fabric8.maven.url.internal;

import java.io.IOException;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.fabric8.maven.MavenResolver;
import io.fabric8.maven.util.MavenConfigurationImpl;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Repository;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.eclipse.aether.transfer.ArtifactTransferException;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.ops4j.util.property.PropertiesPropertyResolver;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.*;

/**
 * Test cases for consecutive resolution attempts
 */
public class AetherResolutionWithHintsTest {

    private static Server server;
    private static int port;

    private static ExecutorService pool = Executors.newFixedThreadPool(1);

    @BeforeClass
    public static void startJetty() throws Exception {
        server = new Server(0);
        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request,
                               HttpServletResponse response) throws IOException, ServletException {
                try {
                    int port = baseRequest.getUri().getPort();
                    if (port == 3333) {
                        // explicit timeout
                        Thread.sleep(2000);
                    }
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                } catch (Exception ignored) {
                } finally {
                    baseRequest.setHandled(true);
                }
            }
        });
        server.start();
        port = ((NetworkConnector) server.getConnectors()[0]).getLocalPort();
    }

    @Test
    public void hintedResolution() throws Exception {
        final MavenConfigurationImpl mavenConfiguration = mavenConfiguration();
        mavenConfiguration.setSettings(settingsWithProxy());
        MavenResolver resolver = new AetherBasedResolver(mavenConfiguration);

        try {
            resolver.download("mvn:org.ops4j.pax.web/pax-web-api/1");
            fail("Resolution should fail");
        } catch (IOException e) {
            RepositoryException exception = ((AetherBasedResolver)resolver).findAetherException(e);
            assertNotNull(exception);
            assertTrue(exception instanceof ArtifactResolutionException);
            ArtifactResolutionException are = (ArtifactResolutionException) exception;
            assertThat(are.getResult().getExceptions().size(), equalTo(3));
            assertTrue("Non-retryable exception", are.getResult().getExceptions().get(0) instanceof ArtifactNotFoundException);
            assertTrue("Non-retryable exception", are.getResult().getExceptions().get(1) instanceof ArtifactNotFoundException);
            assertTrue("Retryable exception", are.getResult().getExceptions().get(2) instanceof ArtifactTransferException);
            assertFalse("Retryable exception", are.getResult().getExceptions().get(2) instanceof ArtifactNotFoundException);

            try {
                // try again with exception hint
                resolver.download("mvn:org.ops4j.pax.web/pax-web-api/1", e);
                fail("Resolution should fail");
            } catch (IOException e2) {
                exception = ((AetherBasedResolver)resolver).findAetherException(e2);
                assertNotNull(exception);
                assertTrue(exception instanceof ArtifactResolutionException);
                are = (ArtifactResolutionException) exception;
                assertThat(are.getResult().getExceptions().size(), equalTo(1));
                assertTrue("Retryable exception", are.getResult().getExceptions().get(0) instanceof ArtifactTransferException);
                assertFalse("Retryable exception", are.getResult().getExceptions().get(0) instanceof ArtifactNotFoundException);
            }
        } finally {
            resolver.close();
        }
    }

    @AfterClass
    public static void stopJetty() throws Exception {
        server.stop();
        pool.shutdown();
    }

    private MavenConfigurationImpl mavenConfiguration() {
        Properties properties = new Properties();
        properties.setProperty("pid.localRepository", "target/" + UUID.randomUUID().toString());
        properties.setProperty("pid.globalChecksumPolicy", "ignore");
        properties.setProperty("pid.timeout", "1000");
        properties.setProperty("pid.connection.retryCount", "0");
        return new MavenConfigurationImpl(new PropertiesPropertyResolver(properties), "pid");
    }

    private Settings settingsWithProxy()
    {
        Settings settings = new Settings();
        Proxy proxy = new Proxy();
        proxy.setId("proxy");
        proxy.setHost("localhost");
        proxy.setPort(port);
        proxy.setProtocol("http");
        settings.setProxies(Collections.singletonList(proxy));

        Profile defaultProfile = new Profile();
        defaultProfile.setId("default");
        Repository repo1 = new Repository();
        repo1.setId("repo1");
        repo1.setUrl("http://localhost:1111/repository");
        Repository repo2 = new Repository();
        repo2.setId("repo2");
        repo2.setUrl("http://localhost:2222/repository");
        Repository repo3 = new Repository();
        repo3.setId("repo3");
        repo3.setUrl("http://localhost:3333/repository");
        defaultProfile.addRepository(repo1);
        defaultProfile.addRepository(repo2);
        defaultProfile.addRepository(repo3);

        settings.addProfile(defaultProfile);
        settings.addActiveProfile("default");
        return settings;
    }

}
