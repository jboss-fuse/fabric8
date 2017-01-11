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
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collections;

import io.fabric8.api.RuntimeProperties;
import io.fabric8.maven.AetherResolutionSupport;
import io.fabric8.maven.MavenResolver;
import org.apache.commons.io.FileUtils;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Non-mocked test for Aether resolution via {@link MavenDownloadProxyServlet}
 */
public class MavenProxyResolutionTest extends AetherResolutionSupport {

    private RuntimeProperties runtime;

    @Before
    public void init() throws IOException {
        super.init();
        runtime = mock(RuntimeProperties.class);
        when(runtime.getDataPath()).thenReturn(Paths.get("target/data/tmp"));
    }

    @Test
    public void releaseIsAvailableInRemoteRepositoryNotUpdatingRelease() throws IOException, InvalidMavenArtifactRequest {
        File remoteRepository = initFileRepository("rr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.singletonList(remoteRepository))
                .withUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS)
                .build();

        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, runtime, null, 1, 0);

        mvnInstall(remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0", at("10:00"), "a");

        File file = servlet.download("io/fabric8/test/universalis-api/0.1.0/universalis-api-0.1.0.jar");

        // first resolution
        assertThat(FileUtils.readFileToString(file), equalTo("a"));

        // don't do that, it's not proper use of maven. But sometimes we just have another deployment to public repository...
        mvnInstall(remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0", at("11:00"), "b");

        // second resolution
        file = servlet.download("io/fabric8/test/universalis-api/0.1.0/universalis-api-0.1.0.jar");
        assertThat("Artifact won't be updated for release version",
                FileUtils.readFileToString(file), equalTo("a"));
    }

    @Test
    public void releaseIsAvailableInRemoteRepositoryUpdatingRelease() throws IOException, InvalidMavenArtifactRequest {
        File remoteRepository = initFileRepository("rr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.singletonList(remoteRepository))
                .withReleaseUpdates()
                .withUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS)
                .build();

        MavenDownloadProxyServlet servlet = new MavenDownloadProxyServlet(resolver, runtime, null, 1, 0);

        mvnInstall(remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0", at("10:00"), "a");

        File file = servlet.download("io/fabric8/test/universalis-api/0.1.0/universalis-api-0.1.0.jar");

        // first resolution
        assertThat(FileUtils.readFileToString(file), equalTo("a"));

        // don't do that, it's not proper use of maven. But sometimes we just have another deployment to public repository...
        mvnInstall(remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0", at("11:00"), "b");

        // second resolution
        file = servlet.download("io/fabric8/test/universalis-api/0.1.0/universalis-api-0.1.0.jar");
        assertThat("Artifact will be updated though it's not proper usage of Maven",
                FileUtils.readFileToString(file), equalTo("b"));

    }

}
