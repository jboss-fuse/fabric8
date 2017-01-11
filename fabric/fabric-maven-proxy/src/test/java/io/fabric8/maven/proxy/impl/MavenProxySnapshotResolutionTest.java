/*
 *  Copyright 2005-2017 Red Hat, Inc.
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

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import io.fabric8.api.RuntimeProperties;
import io.fabric8.maven.AetherResolutionSupport;
import io.fabric8.maven.MavenResolver;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MavenProxySnapshotResolutionTest extends AetherResolutionSupport {

    private RuntimeProperties runtime;

    @Before
    public void init() throws IOException {
        super.init();
        runtime = mock(RuntimeProperties.class);
        when(runtime.getDataPath()).thenReturn(Paths.get("target/data/tmp"));
    }

    private Metadata readMetadata(File file) throws IOException {
        try {
            return new MetadataXpp3Reader().read(new FileReader(file));
        } catch (XmlPullParserException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Test
    public void snapshotIsAvailableInDefaultRepository() throws IOException, InvalidMavenArtifactRequest {
        File defaultRepository = initFileRepository("dr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.<File>emptyList())
                .withUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_NEVER)
                .withDefaultRepositories(Collections.singletonList(defaultRepository))
                .build();

        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, runtime, null, 1, 0);
        servlet.start();

        mvnInstall(defaultRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"), "a");

        // Here's expected state of repository where SNAPSHOT was `mvn install`ed
        assertFalse(new File(defaultRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml").isFile());
        assertTrue(new File(defaultRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata-local.xml").isFile());

        File file = servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml");

        Metadata metadata = readMetadata(file);

        boolean checked = false;
        assertThat(metadata.getVersioning().getSnapshot().isLocalCopy(), is(true));
        for (SnapshotVersion snapshotVersion : metadata.getVersioning().getSnapshotVersions()) {
            if ("jar".equals(snapshotVersion.getExtension())) {
                assertThat(snapshotVersion.getVersion(), is("0.1.0-SNAPSHOT"));
                checked = true;
            }
        }
        assertTrue("We should find snapshot metadata", checked);

        // if metadata says it's "0.1.0-SNAPSHOT", we should have no problem downloading this artifact without
        // version transformation
        file = servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-SNAPSHOT.jar");
        assertThat(FileUtils.readFileToString(file), equalTo("a"));

        mvnInstall(defaultRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"), "b");
        file = servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-SNAPSHOT.jar");
        assertThat("No policy should prevent us from seeing newer snapshot from defaultRepository",
                FileUtils.readFileToString(file), equalTo("b"));
    }

    @Test
    public void snapshotIsAvailableInDefaultRepositoryActingAsRemote() throws IOException, InvalidMavenArtifactRequest {
        File differentLocalRepository = initFileRepository("dlr");
        File defaultRepository = initFileRepository("dr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.<File>emptyList())
                .withUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_NEVER)
                .withDefaultRepositories(Collections.singletonList(defaultRepository))
                .build();

        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, runtime, null, 1, 0);
        servlet.start();

        mvnDeploy(differentLocalRepository, defaultRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"), "a");

        // Here's expected state of repository where SNAPSHOT was `mvn deploy`ed
        assertFalse(new File(defaultRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata-local.xml").isFile());
        assertTrue(new File(defaultRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml").isFile());

        File file = servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml");

        Metadata metadata = readMetadata(file);

        boolean checked = false;
        assertThat(metadata.getVersioning().getSnapshot().isLocalCopy(), is(false));
        for (SnapshotVersion snapshotVersion : metadata.getVersioning().getSnapshotVersions()) {
            if ("jar".equals(snapshotVersion.getExtension())) {
                assertThat(snapshotVersion.getVersion(), is("0.1.0-20170101.100000-1"));
                checked = true;
            }
        }
        assertTrue("We should find snapshot metadata", checked);

        // download artifact using version from metadata
        file = servlet.download("io/fabric8/test/universalis-api/0.1.0-20170101.100000-1/universalis-api-0.1.0-20170101.100000-1.jar");
        assertThat(FileUtils.readFileToString(file), equalTo("a"));

        mvnDeploy(differentLocalRepository, defaultRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"), "b");
        file = servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml");
        metadata = readMetadata(file);
        assertThat("No policy should prevent us from seeing newer snapshot from defaultRepository",
                metadata.getVersioning().getSnapshotVersions().get(0).getVersion(), is("0.1.0-20170101.110000-2"));
    }

    @Test
    public void snapshotIsAvailableInRemoteRepositoryInNewerVersion() throws IOException, InvalidMavenArtifactRequest {
        File differentLocalRepository = initFileRepository("dlr");
        File remoteRepository = initFileRepository("rr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.singletonList(remoteRepository))
                .withUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_DAILY)
                .build();

        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, runtime, null, 1, 0);
        servlet.start();

        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"),"a");
        // first resolution
        servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml");
        servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-20170101.110000-1.jar");

        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("12:00"),"b");

        // second resolution, subject to update policy
        File file = servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml");
        Metadata metadata = readMetadata(file);
        assertThat("Policy prevents from fetching changed metadata",
                metadata.getVersioning().getSnapshotVersions().get(0).getVersion(), is("0.1.0-20170101.110000-1"));

        assertTrue(new File(localRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-20170101.110000-1.jar").isFile());
        assertFalse(new File(localRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-20170101.120000-2.jar").isFile());
        assertTrue(new File(remoteRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-20170101.120000-2.jar").isFile());
    }

    @Test
    public void snapshotIsAvailableInRemoteRepositoryInNewerVersionUpdateAlways() throws IOException, InvalidMavenArtifactRequest {
        File differentLocalRepository = initFileRepository("dlr");
        File remoteRepository = initFileRepository("rr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.singletonList(remoteRepository))
                .withUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS)
                .build();

        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, runtime, null, 1, 0);
        servlet.start();

        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"),"a");
        // first resolution
        servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml");
        servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-20170101.110000-1.jar");

        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("12:00"),"b");

        // second resolution, subject to update policy
        File file = servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml");
        Metadata metadata = readMetadata(file);
        assertThat("Policy forces fetching changed metadata",
                metadata.getVersioning().getSnapshotVersions().get(0).getVersion(), is("0.1.0-20170101.120000-2"));

        assertTrue(new File(localRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-20170101.110000-1.jar").isFile());
        assertFalse(new File(localRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-20170101.120000-2.jar").isFile());
        assertTrue(new File(remoteRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-20170101.110000-1.jar").isFile());
        assertTrue(new File(remoteRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-20170101.120000-2.jar").isFile());
    }

    @Test
    public void snapshotIsAvailableInLocalAndDefaultRepository() throws IOException, InvalidMavenArtifactRequest {
        File defaultRepository = initFileRepository("dr");
        MavenResolver resolver = new ResolverBuilder()
                .withDefaultRepositories(Collections.singletonList(defaultRepository))
                .build();

        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, runtime, null, 1, 0);
        servlet.start();

        mvnInstall(defaultRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"),"a");
        mvnInstall(localRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("12:00"),"b");

        File file = servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml");
        Metadata metadata = readMetadata(file);
        assertThat("Default repository has priority over local repository",
                metadata.getVersioning().getSnapshotVersions().get(0).getUpdated(), is("20170101110000"));
    }

    @Test
    public void snapshotIsAvailableInTwoDefaultRepositories() throws IOException, InvalidMavenArtifactRequest {
        File defaultRepository1 = initFileRepository("dr1");
        File defaultRepository2 = initFileRepository("dr2");
        MavenResolver resolver = new ResolverBuilder()
                .withDefaultRepositories(Arrays.asList(defaultRepository1, defaultRepository2))
                .build();

        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, runtime, null, 1, 0);
        servlet.start();

        mvnInstall(defaultRepository1, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"),"a");
        mvnInstall(defaultRepository2, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"),"b");

        File file = servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml");
        Metadata metadata = readMetadata(file);
        assertThat("Resolution done in first default repository, even if second one has newer artifact",
                metadata.getVersioning().getSnapshotVersions().get(0).getUpdated(), is("20170101100000"));
    }

    @Test
    public void snapshotIsAvailableInTwoRemoteRepositories() throws IOException, InvalidMavenArtifactRequest {
        File differentLocalRepository = initFileRepository("dlr");
        File remoteRepository1 = initFileRepository("rr1");
        File remoteRepository2 = initFileRepository("rr2");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Arrays.asList(remoteRepository1, remoteRepository2))
                .build();

        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, runtime, null, 1, 0);
        servlet.start();

        mvnDeploy(differentLocalRepository, remoteRepository1, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"),"a");
        mvnDeploy(differentLocalRepository, remoteRepository2, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"),"b");
        mvnDeploy(differentLocalRepository, remoteRepository2, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"),"c");

        File file = servlet.download("io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/maven-metadata.xml");
        Metadata metadata = readMetadata(file);
        assertThat("Metadata should aggregate versions from all remote repositories, without duplicates",
                metadata.getVersioning().getSnapshotVersions().size(), is(4));
        assertThat("Latest version should win",
                metadata.getVersioning().getLastUpdated(), is("20170101110000"));
        assertThat("Latest version should win",
                metadata.getVersioning().getSnapshot().getTimestamp(), is("20170101.110000"));
        assertThat("Latest version should win, assume versions are sorted in ascending order",
                metadata.getVersioning().getSnapshotVersions().get(3).getVersion(), is("0.1.0-20170101.110000-2"));
    }

}
