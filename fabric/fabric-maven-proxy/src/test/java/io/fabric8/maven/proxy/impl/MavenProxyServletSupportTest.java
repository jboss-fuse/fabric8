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
package io.fabric8.maven.proxy.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.scr.AbstractRuntimeProperties;
import io.fabric8.deployer.ProjectDeployer;
import io.fabric8.deployer.dto.ProjectRequirements;
import io.fabric8.maven.MavenResolver;
import io.fabric8.maven.url.internal.AetherBasedResolver;
import io.fabric8.maven.util.MavenConfigurationImpl;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.httpclient.methods.multipart.ByteArrayPartSource;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.settings.Proxy;
import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.*;
import org.junit.rules.TestName;
import org.ops4j.util.property.DictionaryPropertyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.fabric8.common.util.Strings.join;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.*;

public class MavenProxyServletSupportTest {

    private RuntimeProperties runtimeProperties;
    private ProjectDeployer projectDeployer;

    protected static final Logger LOG = LoggerFactory.getLogger(MavenProxyServletSupportTest.class);
    @Rule public TestName testname = new TestName();

    @Before
    public void setUp() {
        System.out.println(">>>>>>> Running " + testname.getMethodName() );
        LOG.info(">>>>>>> Running " + testname.getMethodName());
        Properties props = new Properties();
        props.setProperty("localRepository", System.getProperty("java.io.tmpdir"));
        runtimeProperties = EasyMock.createMock(RuntimeProperties.class);
        projectDeployer = EasyMock.createMock(ProjectDeployer.class);
    }

    @After
    public void tearDown() {

    }

    private MavenResolver createResolver() {
        return createResolver(System.getProperty("java.io.tmpdir"), null, null, null, 0, null, null, null);
    }

    private MavenResolver createResolver(String localRepo, List<String> remoteRepos, String proxyProtocol, String proxyHost, int proxyPort, String proxyUsername, String proxyPassword, String proxyNonProxyHosts) {
        Hashtable<String, String> props = new Hashtable<>();
        props.put("localRepository", localRepo);
        if (remoteRepos != null) {
            props.put("repositories", join(remoteRepos, ","));
        }
        MavenConfigurationImpl config = new MavenConfigurationImpl(new DictionaryPropertyResolver(props), null);
        if (proxyProtocol != null) {
            Proxy proxy = new Proxy();
            proxy.setProtocol(proxyProtocol);
            proxy.setHost(proxyHost);
            proxy.setPort(proxyPort);
            proxy.setUsername(proxyUsername);
            proxy.setPassword(proxyPassword);
            proxy.setNonProxyHosts(proxyNonProxyHosts);
            config.getSettings().addProxy(proxy);
        }
        return new AetherBasedResolver(config);
    }

    @Test(timeout=30000)
    public void testMetadataRegex() {
        Matcher m = MavenProxyServletSupport.ARTIFACT_METADATA_URL_REGEX.matcher("groupId/artifactId/version/maven-metadata.xml");
        assertTrue(m.matches());
        assertEquals("maven-metadata.xml", m.group(4));

        m = MavenProxyServletSupport.ARTIFACT_METADATA_URL_REGEX.matcher("groupId/artifactId/version/maven-metadata-local.xml");
        assertTrue(m.matches());
        assertEquals("maven-metadata-local.xml", m.group(4));
        assertEquals("local", m.group(7));

        m = MavenProxyServletSupport.ARTIFACT_METADATA_URL_REGEX.matcher("groupId/artifactId/version/maven-metadata-rep-1234.xml");
        assertTrue(m.matches());
        assertEquals("maven-metadata-rep-1234.xml", m.group(4));
        assertEquals("rep-1234", m.group(7));

        m = MavenProxyServletSupport.ARTIFACT_METADATA_URL_REGEX.matcher("groupId/artifactId/version/maven-metadata.xml.md5");
        assertTrue(m.matches());
        assertEquals("maven-metadata.xml", m.group(4));
    }

    @Test(timeout=30000)
    public void testRepoRegex() {
        Matcher m = MavenProxyServletSupport.REPOSITORY_ID_REGEX.matcher("repo1.maven.org/maven2@id=central");
        assertTrue(m.matches());
        assertEquals("central", m.group(2));

        m = MavenProxyServletSupport.REPOSITORY_ID_REGEX.matcher("https://repo.fusesource.com/nexus/content/repositories/releases@id=fusereleases");
        assertTrue(m.matches());
        assertEquals("fusereleases", m.group(2));

        m = MavenProxyServletSupport.REPOSITORY_ID_REGEX.matcher("repo1.maven.org/maven2@snapshots@id=central");
        assertTrue(m.matches());
        assertEquals("central", m.group(2));

        m = MavenProxyServletSupport.REPOSITORY_ID_REGEX.matcher("repo1.maven.org/maven2@id=central@snapshots");
        assertTrue(m.matches());
        assertEquals("central", m.group(2));

        m = MavenProxyServletSupport.REPOSITORY_ID_REGEX.matcher("repo1.maven.org/maven2@noreleases@id=central@snapshots");
        assertTrue(m.matches());
        assertEquals("central", m.group(2));
    }

    @Test(timeout=30000, expected = InvalidMavenArtifactRequest.class)
    public void testConvertNullPath() throws InvalidMavenArtifactRequest {
        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(createResolver(), runtimeProperties, projectDeployer, 5);
        servlet.convertToMavenUrl(null);
    }

    @Test(timeout=30000)
    public void testConvertNormalPath() throws InvalidMavenArtifactRequest {
        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(createResolver(), runtimeProperties, projectDeployer, 5);

        assertEquals("groupId:artifactId:extension:version",servlet.convertToMavenUrl("groupId/artifactId/version/artifactId-version.extension"));
        assertEquals("group.id:artifactId:extension:version",servlet.convertToMavenUrl("group/id/artifactId/version/artifactId-version.extension"));
        assertEquals("group.id:artifact.id:extension:version",servlet.convertToMavenUrl("group/id/artifact.id/version/artifact.id-version.extension"));

        assertEquals("group-id:artifactId:extension:version",servlet.convertToMavenUrl("group-id/artifactId/version/artifactId-version.extension"));
        assertEquals("group-id:artifact-id:extension:version",servlet.convertToMavenUrl("group-id/artifact-id/version/artifact-id-version.extension"));
        assertEquals("group-id:my-artifact-id:extension:version",servlet.convertToMavenUrl("group-id/my-artifact-id/version/my-artifact-id-version.extension"));

        //Some real cases
        assertEquals("org.apache.camel.karaf:apache-camel:jar:LATEST",servlet.convertToMavenUrl("org/apache/camel/karaf/apache-camel/LATEST/apache-camel-LATEST.jar"));
        assertEquals("org.apache.cxf.karaf:apache-cxf:jar:LATEST",servlet.convertToMavenUrl("org/apache/cxf/karaf/apache-cxf/LATEST/apache-cxf-LATEST.jar"));
        assertEquals("io.fabric8:fabric8-karaf:jar:LATEST",servlet.convertToMavenUrl("io/fabric8/fabric8-karaf/LATEST/fabric8-karaf-LATEST.jar"));

        //Try extensions with a dot
        assertEquals("io.fabric8:fabric8-karaf:zip:LATEST",servlet.convertToMavenUrl("io/fabric8/fabric8-karaf/LATEST/fabric8-karaf-LATEST.zip"));
    }

    @Test(timeout=30000)
    public void testConvertNormalPathWithClassifier() throws InvalidMavenArtifactRequest {
        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(createResolver(), runtimeProperties, projectDeployer, 5);

        assertEquals("groupId:artifactId:extension:classifier:version",servlet.convertToMavenUrl("groupId/artifactId/version/artifactId-version-classifier.extension"));
        assertEquals("group.id:artifactId:extension:classifier:version",servlet.convertToMavenUrl("group/id/artifactId/version/artifactId-version-classifier.extension"));
        assertEquals("group.id:artifact.id:extension:classifier:version",servlet.convertToMavenUrl("group/id/artifact.id/version/artifact.id-version-classifier.extension"));

        assertEquals("group.id:artifact.id:extension.sha1:classifier:version",servlet.convertToMavenUrl("group/id/artifact.id/version/artifact.id-version-classifier.extension.sha1"));
        assertEquals("group.id:artifact.id:extension.md5:classifier:version",servlet.convertToMavenUrl("group/id/artifact.id/version/artifact.id-version-classifier.extension.md5"));

        assertEquals("group-id:artifactId:extension:classifier:version",servlet.convertToMavenUrl("group-id/artifactId/version/artifactId-version-classifier.extension"));
        assertEquals("group-id:artifact-id:extension:classifier:version",servlet.convertToMavenUrl("group-id/artifact-id/version/artifact-id-version-classifier.extension"));
        assertEquals("group-id:my-artifact-id:extension:classifier:version",servlet.convertToMavenUrl("group-id/my-artifact-id/version/my-artifact-id-version-classifier.extension"));

        //Some real cases
        assertEquals("org.apache.camel.karaf:apache-camel:xml:features:LATEST",servlet.convertToMavenUrl("org/apache/camel/karaf/apache-camel/LATEST/apache-camel-LATEST-features.xml"));
        assertEquals("org.apache.cxf.karaf:apache-cxf:xml:features:LATEST",servlet.convertToMavenUrl("org/apache/cxf/karaf/apache-cxf/LATEST/apache-cxf-LATEST-features.xml"));
        assertEquals("io.fabric8:fabric8-karaf:xml:features:LATEST",servlet.convertToMavenUrl("io/fabric8/fabric8-karaf/LATEST/fabric8-karaf-LATEST-features.xml"));
        assertEquals("io.fabric8:fabric8-karaf:xml:features:7-1-x-fuse-01",servlet.convertToMavenUrl("io/fabric8/fabric8-karaf/7-1-x-fuse-01/fabric8-karaf-7-1-x-fuse-01-features.xml"));

        //Try extensions with a dot
        assertEquals("io.fabric8:fabric8-karaf:zip:distro:LATEST",servlet.convertToMavenUrl("io/fabric8/fabric8-karaf/LATEST/fabric8-karaf-LATEST-distro.zip"));
    }

    @Test(timeout=30000)
    public void testStartServlet() throws Exception {
        String old = System.getProperty("karaf.data");
        System.setProperty("karaf.data", new File("target").getCanonicalPath());
        try {
            MavenResolver resolver = createResolver();
            MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, runtimeProperties, projectDeployer, 5);
            servlet.start();
        } finally {
            if (old != null) {
                System.setProperty("karaf.data", old);
            }
        }
    }

    @Test(timeout=30000)
    @Ignore("CXF-6704 - we have to wait for CXF 3.1.5 to have httpclient 4.5.x")
    public void testDownloadUsingAuthenticatedProxy() throws Exception {
        testDownload(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                String proxyAuth = request.getHeader("Proxy-Authorization");
                if (proxyAuth == null || proxyAuth.trim().equals("")) {
                    response.setStatus(HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED);
                    response.addHeader("Proxy-Authenticate", "Basic realm=\"Proxy Server\"");
                    baseRequest.setHandled(true);
                } else {
                    response.setStatus(HttpServletResponse.SC_OK);
                    baseRequest.setHandled(true);
                    response.getOutputStream().write(new byte[] { 0x42 });
                    response.getOutputStream().close();
                }
            }
        });
    }

    @Test(timeout=30000)
    public void testDownloadUsingNonAuthenticatedProxy() throws Exception {
        testDownload(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                response.setStatus(HttpServletResponse.SC_OK);
                baseRequest.setHandled(true);
                response.getOutputStream().write(new byte[] { 0x42 });
                response.getOutputStream().close();
            }
        });
    }

    @Test(timeout=30000)
    public void testDownloadMetadata() throws Exception {
        final String old = System.getProperty("karaf.data");
        System.setProperty("karaf.data", new File("target").getCanonicalPath());
        FileUtils.deleteDirectory(new File("target/tmp"));

        Server server = new Server(0);
        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                String result = null;
                if ("/repo1/org/apache/camel/camel-core/maven-metadata.xml".equals(target)) {
                    result =
                            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                            "<metadata>\n" +
                            "  <groupId>org.apache.camel</groupId>\n" +
                            "  <artifactId>camel-core</artifactId>\n" +
                            "  <versioning>\n" +
                            "    <latest>2.14.0</latest>\n" +
                            "    <release>2.14.0</release>\n" +
                            "    <versions>\n" +
                            "      <version>1.6.1</version>\n" +
                            "      <version>1.6.2</version>\n" +
                            "      <version>1.6.3</version>\n" +
                            "      <version>1.6.4</version>\n" +
                            "      <version>2.0-M2</version>\n" +
                            "      <version>2.0-M3</version>\n" +
                            "      <version>2.0.0</version>\n" +
                            "      <version>2.1.0</version>\n" +
                            "      <version>2.2.0</version>\n" +
                            "      <version>2.3.0</version>\n" +
                            "      <version>2.4.0</version>\n" +
                            "      <version>2.5.0</version>\n" +
                            "      <version>2.6.0</version>\n" +
                            "      <version>2.7.0</version>\n" +
                            "      <version>2.7.1</version>\n" +
                            "      <version>2.7.2</version>\n" +
                            "      <version>2.7.3</version>\n" +
                            "      <version>2.7.4</version>\n" +
                            "      <version>2.7.5</version>\n" +
                            "      <version>2.8.0</version>\n" +
                            "      <version>2.8.1</version>\n" +
                            "      <version>2.8.2</version>\n" +
                            "      <version>2.8.3</version>\n" +
                            "      <version>2.8.4</version>\n" +
                            "      <version>2.8.5</version>\n" +
                            "      <version>2.8.6</version>\n" +
                            "      <version>2.9.0-RC1</version>\n" +
                            "      <version>2.9.0</version>\n" +
                            "      <version>2.9.1</version>\n" +
                            "      <version>2.9.2</version>\n" +
                            "      <version>2.9.3</version>\n" +
                            "      <version>2.9.4</version>\n" +
                            "      <version>2.9.5</version>\n" +
                            "      <version>2.9.6</version>\n" +
                            "      <version>2.9.7</version>\n" +
                            "      <version>2.9.8</version>\n" +
                            "      <version>2.10.0</version>\n" +
                            "      <version>2.10.1</version>\n" +
                            "      <version>2.10.2</version>\n" +
                            "      <version>2.10.3</version>\n" +
                            "      <version>2.10.4</version>\n" +
                            "      <version>2.10.5</version>\n" +
                            "      <version>2.10.6</version>\n" +
                            "      <version>2.10.7</version>\n" +
                            "      <version>2.11.0</version>\n" +
                            "      <version>2.11.1</version>\n" +
                            "      <version>2.11.2</version>\n" +
                            "      <version>2.11.3</version>\n" +
                            "      <version>2.11.4</version>\n" +
                            "      <version>2.12.0</version>\n" +
                            "      <version>2.12.1</version>\n" +
                            "      <version>2.12.2</version>\n" +
                            "      <version>2.12.3</version>\n" +
                            "      <version>2.12.4</version>\n" +
                            "      <version>2.13.0</version>\n" +
                            "      <version>2.13.1</version>\n" +
                            "      <version>2.13.2</version>\n" +
                            "      <version>2.14.0</version>\n" +
                            "    </versions>\n" +
                            "    <lastUpdated>20140918132816</lastUpdated>\n" +
                            "  </versioning>\n" +
                            "</metadata>\n" +
                            "\n";
                } else if ("/repo2/org/apache/camel/camel-core/maven-metadata.xml".equals(target)) {
                    result =
                            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                            "<metadata modelVersion=\"1.1.0\">\n" +
                            "  <groupId>org.apache.camel</groupId>\n" +
                            "  <artifactId>camel-core</artifactId>\n" +
                            "  <versioning>\n" +
                            "    <latest>2.14.0.redhat-620034</latest>\n" +
                            "    <release>2.14.0.redhat-620034</release>\n" +
                            "    <versions>\n" +
                            "      <version>2.10.0.redhat-60074</version>\n" +
                            "      <version>2.12.0.redhat-610312</version>\n" +
                            "      <version>2.12.0.redhat-610328</version>\n" +
                            "      <version>2.12.0.redhat-610355</version>\n" +
                            "      <version>2.12.0.redhat-610378</version>\n" +
                            "      <version>2.12.0.redhat-610396</version>\n" +
                            "      <version>2.12.0.redhat-610399</version>\n" +
                            "      <version>2.12.0.redhat-610401</version>\n" +
                            "      <version>2.12.0.redhat-610402</version>\n" +
                            "      <version>2.12.0.redhat-611403</version>\n" +
                            "      <version>2.12.0.redhat-611405</version>\n" +
                            "      <version>2.12.0.redhat-611406</version>\n" +
                            "      <version>2.12.0.redhat-611408</version>\n" +
                            "      <version>2.12.0.redhat-611409</version>\n" +
                            "      <version>2.12.0.redhat-611410</version>\n" +
                            "      <version>2.12.0.redhat-611411</version>\n" +
                            "      <version>2.12.0.redhat-611412</version>\n" +
                            "      <version>2.14.0.redhat-620031</version>\n" +
                            "      <version>2.14.0.redhat-620033</version>\n" +
                            "      <version>2.14.0.redhat-620034</version>\n" +
                            "    </versions>\n" +
                            "    <lastUpdated>20141019130841</lastUpdated>\n" +
                            "  </versioning>\n" +
                            "</metadata>\n" +
                            "\n";
                }
                if (result == null) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    baseRequest.setHandled(true);
                    response.getOutputStream().close();
                } else {
                    response.setStatus(HttpServletResponse.SC_OK);
                    baseRequest.setHandled(true);
                    response.getOutputStream().write(result.getBytes());
                    response.getOutputStream().close();
                }
            }
        });
        server.start();

        try {
            int localPort = ((ServerConnector)server.getConnectors()[0]).getLocalPort();
            List<String> remoteRepos = Arrays.asList("http://relevant.not/repo1@id=repo1,http://relevant.not/repo2@id=repo2");
            RuntimeProperties props = new MockRuntimeProperties();
            // TODO: local repo should point to target/tmp
            MavenResolver resolver = createResolver("target/tmp", remoteRepos, "http", "localhost", localPort, "fuse", "fuse", null);
            MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, props, projectDeployer, 5);

            AsyncContext context = EasyMock.createMock(AsyncContext.class);
            EasyMock.makeThreadSafe(context, true);

            final Map<String, Object> attributes = new HashMap<>();
            HttpServletRequest request = EasyMock.createMock(HttpServletRequest.class);
            HttpServletRequest requestWrapper = new HttpServletRequestWrapper(request) {
                @Override
                public Object getAttribute(String name) {
                    return attributes.get(name);
                }

                @Override
                public void setAttribute(String name, Object o) {
                    attributes.put(name, o);
                }
            };
            EasyMock.makeThreadSafe(request, true);
            EasyMock.expect(request.getMethod()).andReturn("GET");
            EasyMock.expect(request.getPathInfo()).andReturn("org/apache/camel/camel-core/maven-metadata.xml");
            EasyMock.expect(request.startAsync()).andReturn(context);
            context.setTimeout(EasyMock.anyInt());
            EasyMock.expectLastCall();
            context.addListener((AsyncListener) EasyMock.anyObject());
            EasyMock.expectLastCall();

            HttpServletResponse response = EasyMock.createMock(HttpServletResponse.class);
            EasyMock.makeThreadSafe(response, true);
            response.setStatus(EasyMock.anyInt());
            EasyMock.expectLastCall().anyTimes();
            response.setContentLength(EasyMock.anyInt());
            EasyMock.expectLastCall().anyTimes();
            response.setContentType((String) EasyMock.anyObject());
            EasyMock.expectLastCall().anyTimes();
            response.setDateHeader((String) EasyMock.anyObject(), EasyMock.anyLong());
            EasyMock.expectLastCall().anyTimes();
            response.setHeader((String) EasyMock.anyObject(), (String) EasyMock.anyObject());
            EasyMock.expectLastCall().anyTimes();
            response.flushBuffer();
            EasyMock.expectLastCall().anyTimes();

            final CountDownLatch latchDispatch = new CountDownLatch(1);
            context.dispatch();
            EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {
                @Override
                public Object answer() throws Throwable {
                    latchDispatch.countDown();
                    return null;
                }
            });

            EasyMock.replay(request, response, context);

            servlet.start();
            servlet.doGet(requestWrapper, response);

            latchDispatch.await();

            EasyMock.verify(request, response, context);

            EasyMock.reset(request, response, context);
            EasyMock.expect(request.getPathInfo()).andReturn("org/apache/camel/camel-core/maven-metadata.xml");
            EasyMock.expect(request.startAsync()).andReturn(context);
            context.setTimeout(EasyMock.anyInt());
            EasyMock.expectLastCall();
            context.addListener((AsyncListener) EasyMock.anyObject());
            EasyMock.expectLastCall();
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            EasyMock.expect(response.getOutputStream()).andReturn(new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    baos.write(b);
                }
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    baos.write(b, off, len);
                }
                @Override
                public boolean isReady() {
                    return true;
                }
                @Override
                public void setWriteListener(WriteListener writeListener) {
                }
            }).anyTimes();
            response.flushBuffer();
            EasyMock.expectLastCall().anyTimes();
            final CountDownLatch latchComplete = new CountDownLatch(1);
            context.complete();
            EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {
                @Override
                public Object answer() throws Throwable {
                    latchComplete.countDown();
                    return null;
                }
            });
            EasyMock.replay(request, response, context);

            servlet.doGet(requestWrapper, response);

            EasyMock.verify(request, response, context);

            org.apache.maven.artifact.repository.metadata.Metadata m =
                    new MetadataXpp3Reader().read( new ByteArrayInputStream( baos.toByteArray() ), false );
            assertEquals("2.14.0.redhat-620034", m.getVersioning().getLatest());
            assertTrue(m.getVersioning().getVersions().contains("2.10.4"));
            assertTrue(m.getVersioning().getVersions().contains("2.12.0.redhat-610399"));

            EasyMock.verify(request, response, context);
        } finally {
            server.stop();
            if (old != null) {
                System.setProperty("karaf.data", old);
            }
        }
    }

    private void testDownload(Handler serverHandler) throws Exception {
        final String old = System.getProperty("karaf.data");
        System.setProperty("karaf.data", new File("target").getCanonicalPath());
        FileUtils.deleteDirectory(new File("target/tmp"));

        Server server = new Server(0);
        server.setHandler(serverHandler);
        server.start();

        try {
            int localPort = ((ServerConnector)server.getConnectors()[0]).getLocalPort();
            List<String> remoteRepos = Arrays.asList("http://relevant.not/maven2@id=central");
            RuntimeProperties props = new MockRuntimeProperties();
            // TODO: local repo should point to target/tmp
            MavenResolver resolver = createResolver("target/tmp", remoteRepos, "http", "localhost", localPort, "fuse", "fuse", null);
            MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, props, projectDeployer, 5);

            AsyncContext context = EasyMock.createMock(AsyncContext.class);
            EasyMock.makeThreadSafe(context, true);

            final Map<String, Object> attributes = new HashMap<>();
            HttpServletRequest request = EasyMock.createMock(HttpServletRequest.class);
            HttpServletRequest requestWrapper = new HttpServletRequestWrapper(request) {
                @Override
                public Object getAttribute(String name) {
                    return attributes.get(name);
                }

                @Override
                public void setAttribute(String name, Object o) {
                    attributes.put(name, o);
                }
            };
            EasyMock.makeThreadSafe(request, true);
            EasyMock.expect(request.getMethod()).andReturn("GET");
            EasyMock.expect(request.getPathInfo()).andReturn("org.apache.camel/camel-core/2.13.0/camel-core-2.13.0-sources.jar");
            EasyMock.expect(request.startAsync()).andReturn(context);
            context.setTimeout(EasyMock.anyInt());
            EasyMock.expectLastCall();
            context.addListener((AsyncListener) EasyMock.anyObject());
            EasyMock.expectLastCall();

            HttpServletResponse response = EasyMock.createMock(HttpServletResponse.class);
            EasyMock.makeThreadSafe(response, true);
            response.setStatus(EasyMock.anyInt());
            EasyMock.expectLastCall();
            response.setContentLength(EasyMock.anyInt());
            EasyMock.expectLastCall();
            response.setContentType((String) EasyMock.anyObject());
            EasyMock.expectLastCall();
            response.setDateHeader((String) EasyMock.anyObject(), EasyMock.anyLong());
            EasyMock.expectLastCall();
            response.setHeader((String) EasyMock.anyObject(), (String) EasyMock.anyObject());
            EasyMock.expectLastCall().anyTimes();

            final CountDownLatch latchDispatch = new CountDownLatch(1);
            context.dispatch();
            EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {
                @Override
                public Object answer() throws Throwable {
                    latchDispatch.countDown();
                    return null;
                }
            });

            EasyMock.replay(request, response, context);

            servlet.start();
            servlet.doGet(requestWrapper, response);

            latchDispatch.await();

            EasyMock.verify(request, response, context);

            //
            // Subsequent call
            //
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ServletOutputStream outputStream = new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    baos.write(b);
                }
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    baos.write(b, off, len);
                }
                @Override
                public boolean isReady() {
                    return true;
                }
                @Override
                public void setWriteListener(WriteListener writeListener) {
                }
            };

            while (true) {
                long size = (Long) attributes.get(AsynchronousFileChannel.class.getName() + ".size");
                long pos = (Long) attributes.get(AsynchronousFileChannel.class.getName() + ".position");
                ByteBuffer buffer = (ByteBuffer) attributes.get(ByteBuffer.class.getName());
                if (pos + buffer.position() >= size) {
                    break;
                }

                EasyMock.reset(request, response, context);
                EasyMock.expect(request.getPathInfo()).andReturn("org.apache.camel/camel-core/2.13.0/camel-core-2.13.0-sources.jar");
                EasyMock.expect(request.startAsync()).andReturn(context);
                context.setTimeout(EasyMock.anyInt());
                EasyMock.expectLastCall();
                context.addListener((AsyncListener) EasyMock.anyObject());
                EasyMock.expectLastCall();
                final CountDownLatch latch = new CountDownLatch(1);
                context.dispatch();
                EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {
                    @Override
                    public Object answer() throws Throwable {
                        latch.countDown();
                        return null;
                    }
                });
                EasyMock.expect(response.getOutputStream()).andReturn(outputStream);
                EasyMock.replay(request, response, context);
                servlet.doGet(requestWrapper, response);
                latch.await();
                EasyMock.verify(request, response, context);
            }

            //
            // Last calls
            //

            EasyMock.reset(request, response, context);

            EasyMock.expect(request.getPathInfo()).andReturn("org.apache.camel/camel-core/2.13.0/camel-core-2.13.0-sources.jar");
            EasyMock.expect(request.startAsync()).andReturn(context);
            context.setTimeout(EasyMock.anyInt());
            EasyMock.expectLastCall();
            context.addListener((AsyncListener) EasyMock.anyObject());
            EasyMock.expectLastCall();
            final CountDownLatch latchComplete = new CountDownLatch(1);
            context.complete();
            EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {
                @Override
                public Object answer() throws Throwable {
                    latchComplete.countDown();
                    return null;
                }
            });
            EasyMock.expect(response.getOutputStream()).andReturn(outputStream);
            response.flushBuffer();
            EasyMock.expectLastCall();
            EasyMock.replay(request, response, context);
            servlet.doGet(requestWrapper, response);
            latchComplete.await();
            EasyMock.verify(request, response, context);
            Assert.assertArrayEquals(new byte[] { 0x42 }, baos.toByteArray());

        } finally {
            server.stop();
            if (old != null) {
                System.setProperty("karaf.data", old);
            }
        }
    }

    @Test(timeout=30000)
    public void testJarUploadFullMvnPath() throws Exception {
        String jarPath = "org.acme/acme-core/1.0/acme-core-1.0.jar";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jas = new JarOutputStream(baos);
        addEntry(jas, "hello.txt", "Hello!".getBytes());
        jas.close();

        byte[] contents = baos.toByteArray();

        testUpload(jarPath, contents, false);
    }

    @Test(timeout=30000)
    public void testJarUploadWithMvnPom() throws Exception {
        String jarPath = "org.acme/acme-core/1.0/acme-core-1.0.jar";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jas = new JarOutputStream(baos);
        addEntry(jas, "hello.txt", "Hello!".getBytes());
        addPom(jas, "org.acme", "acme-core", "1.0");
        jas.close();

        byte[] contents = baos.toByteArray();

        testUpload(jarPath, contents, false);
    }

    @Test(timeout=30000)
    public void testJarUploadNoMvnPath() throws Exception {
        String jarPath = "acme-core-1.0.jar";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jas = new JarOutputStream(baos);
        addEntry(jas, "hello.txt", "Hello!".getBytes());
        jas.close();

        byte[] contents = baos.toByteArray();
        testUpload(jarPath, contents, true);
    }

    @Test(timeout=30000)
    public void testWarUploadFullMvnPath() throws Exception {
        String warPath = "org.acme/acme-ui/1.0/acme-ui-1.0.war";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jas = new JarOutputStream(baos);
        addEntry(jas, "WEB-INF/web.xml", "<web/>".getBytes());
        jas.close();

        byte[] contents = baos.toByteArray();

        testUpload(warPath, contents, false);
    }

    @Test(timeout=30000)
    public void testWarUploadWithMvnPom() throws Exception {
        String warPath = "org.acme/acme-ui/1.0/acme-ui-1.0.war";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jas = new JarOutputStream(baos);
        addEntry(jas, "WEB-INF/web.xml", "<web/>".getBytes());
        addPom(jas, "org.acme", "acme-ui", "1.0");
        jas.close();

        byte[] contents = baos.toByteArray();

        testUpload(warPath, contents, false);
    }

    @Test(timeout=30000)
    public void testWarUploadNoMvnPath() throws Exception {
        String warPath = "acme-ui-1.0.war";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jas = new JarOutputStream(baos);
        addEntry(jas, "WEB-INF/web.xml", "<web/>".getBytes());
        jas.close();

        byte[] contents = baos.toByteArray();

        testUpload(warPath, contents, true);
    }

    @Test
    public void testUploadWithMimeMultipartFormData() throws Exception {
        new File("target/maven/proxy/tmp/multipart").mkdirs();
        System.setProperty("karaf.data", new File("target").getCanonicalPath());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JarOutputStream jas = new JarOutputStream(baos);
        addEntry(jas, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes());
        addEntry(jas, "META-INF/maven/io.fabric8/mybundle/pom.properties", "groupId=io.fabric8\nartifactId=mybundle\nversion=1.0\n".getBytes());
        jas.close();
        byte[] jarBytes = baos.toByteArray();

        RuntimeProperties props = new MockRuntimeProperties();
        MavenResolver resolver = EasyMock.createMock(MavenResolver.class);
        MavenUploadProxyServlet servlet = new MavenUploadProxyServlet(resolver, props, projectDeployer, new File("target/upload"));
        servlet.setFileItemFactory(new DiskFileItemFactory(0, new File("target/maven/proxy/tmp/multipart")));

        HttpServletRequest request = EasyMock.createMock(HttpServletRequest.class);
        HttpServletResponse response = EasyMock.createMock(HttpServletResponse.class);

        FilePart part = new FilePart("file[]", new ByteArrayPartSource("mybundle-1.0.jar", jarBytes));
        MultipartRequestEntity entity = new MultipartRequestEntity(new Part[] { part }, new HttpMethodParams());
        final ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
        entity.writeRequest(requestBytes);
        final byte[] multipartRequestBytes = requestBytes.toByteArray();

        EasyMock.expect(request.getPathInfo()).andReturn("/mybundle-1.0.jar");
        EasyMock.expect(request.getHeader(MavenProxyServletSupport.LOCATION_HEADER)).andReturn(null);
        EasyMock.expect(request.getParameter("profile")).andReturn("my");
        EasyMock.expect(request.getParameter("version")).andReturn("1.0");
        EasyMock.expect(request.getContentType()).andReturn(entity.getContentType()).anyTimes();
        EasyMock.expect(request.getHeader("Content-length")).andReturn(Long.toString(entity.getContentLength())).anyTimes();
        EasyMock.expect(request.getContentLength()).andReturn((int) entity.getContentLength()).anyTimes();
        EasyMock.expect(request.getCharacterEncoding()).andReturn("ISO-8859-1").anyTimes();

        Capture<String> location = EasyMock.newCapture(CaptureType.ALL);
        response.setStatus(HttpServletResponse.SC_ACCEPTED);

        EasyMock.expect(request.getInputStream()).andReturn(new ServletInputStream() {
            private int pos = 0;

            @Override
            public int read() throws IOException {
                return pos >= multipartRequestBytes.length ? -1 : (multipartRequestBytes[pos++] & 0xFF);
            }

            @Override
            public boolean isFinished() {
                return false;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {

            }
        });

        Capture<ProjectRequirements> requirementsCapture = EasyMock.newCapture(CaptureType.FIRST);
        EasyMock.expect(projectDeployer.deployProject(EasyMock.capture(requirementsCapture), EasyMock.eq(true))).andReturn(null);

        EasyMock.replay(resolver, request, response, projectDeployer);

        servlet.doPut(request, response);

        FileInputStream fis = new FileInputStream("target/upload/io.fabric8/mybundle/1.0/mybundle-1.0.jar");
        ByteArrayOutputStream storedBundleBytes = new ByteArrayOutputStream();
        IOUtils.copy(fis, storedBundleBytes);
        fis.close();
        Assert.assertArrayEquals(jarBytes, storedBundleBytes.toByteArray());

        ProjectRequirements pr = requirementsCapture.getValue();
        List<String> bundles = pr.getBundles();
        assertThat(bundles.size(), equalTo(1));
        assertThat(bundles.get(0), equalTo("mvn:io.fabric8/mybundle/1.0"));
        assertThat(pr.getProfileId(), equalTo("my"));
        assertThat(pr.getVersion(), equalTo("1.0"));
        assertThat(pr.getGroupId(), nullValue());
        assertThat(pr.getArtifactId(), nullValue());

        EasyMock.verify(resolver, request, response, projectDeployer);
    }

    private static void addEntry(JarOutputStream jas, String name, byte[] content) throws Exception {
        JarEntry entry = new JarEntry(name);
        jas.putNextEntry(entry);
        if (content != null) {
            jas.write(content);
        }
        jas.closeEntry();
    }

    private static void addPom(JarOutputStream jas, String groupId, String artifactId, String version) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("groupId", groupId);
        properties.setProperty("artifactId", artifactId);
        properties.setProperty("version", version);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        properties.store(baos, null);
        addEntry(jas, String.format("META-INF/maven/%s/%s/%s/pom.properties", groupId, artifactId, version), baos.toByteArray());
    }

    private Map<String, String> testUpload(String path, final byte[] contents, boolean hasLocationHeader) throws Exception {
        return testUpload(path, contents, null, hasLocationHeader);
    }

    private Map<String, String> testUpload(String path, final byte[] contents, String location, boolean hasLocationHeader) throws Exception {
        return testUpload(path, contents, location, null, null, hasLocationHeader);
    }

    private Map<String, String> testUpload(String path, final byte[] contents, String location, String profile, String version, boolean hasLocationHeader) throws Exception {
        final String old = System.getProperty("karaf.data");
        System.setProperty("karaf.data", new File("target").getCanonicalPath());
        FileUtils.deleteDirectory(new File("target/tmp"));

        Server server = new Server(0);
        server.setHandler(new AbstractHandler() {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            }
        });
        server.start();

        try {
            int localPort = ((ServerConnector)server.getConnectors()[0]).getLocalPort();
            List<String> remoteRepos = Arrays.asList("http://relevant.not/maven2@id=central");
            RuntimeProperties props = new MockRuntimeProperties();
            MavenResolver resolver = createResolver("target/tmp", remoteRepos, "http", "localhost", localPort, "fuse", "fuse", null);
            MavenUploadProxyServlet servlet = new MavenUploadProxyServlet(resolver, props, projectDeployer, new File("target/upload"));

            HttpServletRequest request = EasyMock.createMock(HttpServletRequest.class);
            EasyMock.expect(request.getPathInfo()).andReturn(path);
            EasyMock.expect(request.getContentType()).andReturn("text/plain").anyTimes();
            EasyMock.expect(request.getInputStream()).andReturn(new ServletInputStream() {
                private int i;

                @Override
                public int read() throws IOException {
                    if (i >= contents.length) {
                        return -1;
                    }
                    return (contents[i++] & 0xFF);
                }

                @Override
                public boolean isReady() {
                    return false;
                }

                @Override
                public boolean isFinished() {
                    return false;
                }

                @Override
                public void setReadListener(ReadListener readListener) {

                }
            });
            EasyMock.expect(request.getHeader("X-Location")).andReturn(location);
            EasyMock.expect(request.getParameter("profile")).andReturn(profile);
            EasyMock.expect(request.getParameter("version")).andReturn(version);

            final Map<String, String> headers = new HashMap<>();

            HttpServletResponse rm = EasyMock.createMock(HttpServletResponse.class);
            HttpServletResponse response = new HttpServletResponseWrapper(rm) {
                @Override
                public void addHeader(String name, String value) {
                    headers.put(name, value);
                }
            };
            response.setStatus(EasyMock.anyInt());
            EasyMock.expectLastCall().anyTimes();
            response.setContentLength(EasyMock.anyInt());
            EasyMock.expectLastCall().anyTimes();
            response.setContentType((String) EasyMock.anyObject());
            EasyMock.expectLastCall().anyTimes();
            response.setDateHeader((String) EasyMock.anyObject(), EasyMock.anyLong());
            EasyMock.expectLastCall().anyTimes();
            response.setHeader((String) EasyMock.anyObject(), (String) EasyMock.anyObject());
            EasyMock.expectLastCall().anyTimes();

            EasyMock.replay(request, rm);

            servlet.start();
            servlet.doPut(request, response);

            EasyMock.verify(request, rm);

            Assert.assertEquals(hasLocationHeader, headers.containsKey("X-Location"));

            return headers;
        } finally {
            server.stop();
            if (old != null) {
                System.setProperty("karaf.data", old);
            }
        }
    }

    /**
     * To satisfy new container-independent source of properties
     */
    private static class MockRuntimeProperties extends AbstractRuntimeProperties {
        
        @Override
        public Path getDataPath() {
            return Paths.get("target/tmp");
        }

        @Override
        protected String getPropertyInternal(String key, String defaultValue) {
            return null;
        }
    }
}
