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
package io.fabric8.maven;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import org.apache.commons.io.FileUtils;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class AetherSnapshotResolutionTest extends AetherResolutionSupport {

    @Test
    public void snapshotIsAvailableInDefaultRepository() throws IOException {
        File defaultRepository = initFileRepository("dr");
        MavenResolver resolver = new ResolverBuilder()
                .withDefaultRepositories(Collections.singletonList(defaultRepository))
                .build();

        mvnInstall(defaultRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"), "0");

        File file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        assertThat("We should have a file resolved from default repository",
                file.getCanonicalFile(),
                equalTo(new File(defaultRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-SNAPSHOT.jar").getCanonicalFile()));
    }

    @Test
    public void snapshotIsAvailableInLocalAndRemoteRepository() throws IOException {
        File differentLocalRepository = initFileRepository("dlr");
        File remoteRepository = initFileRepository("rr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.singletonList(remoteRepository))
                .build();

        mvnDeploy(localRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"),"a");
        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"),"b");

        File localArtifact = new File(localRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-SNAPSHOT.jar");
        File remoteArtifact = new File(remoteRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-SNAPSHOT.jar");

        assertTrue("Artifact is available in local repo before resolution",
                localArtifact.isFile());

        File file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        assertTrue("Artifact is available in local repo after resolution",
                localArtifact.isFile());
        assertThat(file.getCanonicalFile(), equalTo(localArtifact.getCanonicalFile()));

        assertThat("We resolved a file from local repository without downloading remote version",
                FileUtils.readFileToString(localArtifact), equalTo("a"));
    }

    @Test
    public void snapshotIsAvailableInLocalAndDefaultRepository() throws IOException {
        File defaultRepository = initFileRepository("dr");
        MavenResolver resolver = new ResolverBuilder()
                .withDefaultRepositories(Collections.singletonList(defaultRepository))
                .build();

        mvnInstall(localRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"),"b");
        mvnInstall(defaultRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"),"a");

        File artifact = new File(defaultRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-SNAPSHOT.jar");

        File file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        assertThat(file.getCanonicalFile(), equalTo(artifact.getCanonicalFile()));
    }

    @Test
    public void snapshotIsAvailableInDefaultRepositoryActingAsRemote() throws IOException {
        File defaultRepository = initFileRepository("dr");
        MavenResolver resolver = new ResolverBuilder()
                .withDefaultRepositories(Collections.singletonList(defaultRepository))
                .build();

        mvnDeploy(localRepository, defaultRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"),"a");
        // install newer version in local repository
        mvnInstall(localRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"),"b");

        File artifact = new File(defaultRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-SNAPSHOT.jar");
        File originalArtifact = new File(defaultRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-20170101.100000-1.jar");

        File file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        assertThat(file.getCanonicalFile(), equalTo(artifact.getCanonicalFile()));

        assertThat(FileUtils.readFileToString(artifact), equalTo("a"));
        assertThat("Originally deployed file should also be available",
                FileUtils.readFileToString(originalArtifact), equalTo("a"));
    }

    @Test
    public void snapshotIsAvailableInDefaultRepositoryActingAsRemoteAfterTwoDeployments() throws IOException {
        File defaultRepository = initFileRepository("dr");
        MavenResolver resolver = new ResolverBuilder()
                .withUpdatePolicy("never")
                .withDefaultRepositories(Collections.singletonList(defaultRepository))
                .build();

        mvnDeploy(localRepository, defaultRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"),"a");

        File file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        assertThat(FileUtils.readFileToString(file), equalTo("a"));

        // another deployment
        mvnDeploy(localRepository, defaultRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"),"b");
        // install newer version in local repository
        mvnInstall(localRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("12:00"),"c");

        file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        assertThat("We should get latest SNAPSHOT without checking update policy which is used only for remote repos",
                FileUtils.readFileToString(file), equalTo("b"));
    }

    @Test
    public void snapshotIsAvailableInTwoDefaultRepositories() throws IOException {
        File defaultRepository1 = initFileRepository("dr");
        File defaultRepository2 = initFileRepository("dr");
        MavenResolver resolver = new ResolverBuilder()
                .withDefaultRepositories(Arrays.asList(defaultRepository1, defaultRepository2))
                .build();

        mvnInstall(defaultRepository1, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"),"a");
        mvnInstall(defaultRepository2, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"),"b");

        File artifact = new File(defaultRepository1, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-SNAPSHOT.jar");

        File file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        assertThat("Resolution done in first default repository, even if second one has newer artifact",
                file.getCanonicalFile(), equalTo(artifact.getCanonicalFile()));
    }

    @Test
    public void snapshotIsAvailableInRemoteRepositoryFirstCheck() throws IOException {
        File differentLocalRepository = initFileRepository("dlr");
        File remoteRepository = initFileRepository("rr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.singletonList(remoteRepository))
                .build();

        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"), "0");

        File localArtifact = new File(localRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-SNAPSHOT.jar");
        assertFalse("Artifact is not available in local repo before resolution",
                localArtifact.isFile());

        File file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        assertTrue("Artifact is available in local repo after resolution",
                localArtifact.isFile());
        assertThat(file.getCanonicalFile(), equalTo(localArtifact.getCanonicalFile()));

        // there's org.eclipse.aether.internal.impl.DefaultArtifactResolver.CONFIG_PROP_SNAPSHOT_NORMALIZATION
        // option that copies resolved version to base version
        File remoteCopyOfArtifact = new File(localRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-20170101.100000-1.jar");
        assertTrue("Artifact is also available with transformed version",
                remoteCopyOfArtifact.isFile());
    }

    @Test
    public void snapshotIsAvailableInRemoteRepositoryInNewerVersion() throws IOException {
        File differentLocalRepository = initFileRepository("dlr");
        File remoteRepository = initFileRepository("rr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.singletonList(remoteRepository))
                .build();

        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"), "a");

        File localArtifact = new File(localRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-SNAPSHOT.jar");
        assertFalse("Artifact is not available in local repo before resolution",
                localArtifact.isFile());

        File file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        // first resolution
        assertThat("We should have initially deployed version of artifact",
                FileUtils.readFileToString(file), equalTo("a"));
        assertTrue("Artifact is available in local repo after resolution",
                localArtifact.isFile());

        // deploy changed SNAPSHOT version
        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"), "b");

        // second resolution
        file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        assertThat("Due to update policy, there won't update of metadata",
                FileUtils.readFileToString(file), equalTo("a"));
    }

    @Test
    public void snapshotIsAvailableInRemoteRepositoryInNewerVersionUpdateAlways() throws IOException {
        File differentLocalRepository = initFileRepository("dlr");
        File remoteRepository = initFileRepository("rr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.singletonList(remoteRepository))
                .withUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS)
                .build();

        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("10:00"), "a");

        File file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        // first resolution
        assertThat(FileUtils.readFileToString(file), equalTo("a"));

        // deploy changed SNAPSHOT version
        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0-SNAPSHOT", at("11:00"), "b");

        // let's trick aether a bit
        file.setLastModified(file.lastModified() - 1000L);

        // second resolution
        file = resolver.download("io.fabric8.test/universalis-api/0.1.0-SNAPSHOT");
        assertThat("Due to update policy, we'll have newer version available",
                FileUtils.readFileToString(file), equalTo("b"));

        // two remote versions of SNAPSHOTs
        File a1 = new File(localRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-20170101.100000-1.jar");
        File a2 = new File(localRepository, "io/fabric8/test/universalis-api/0.1.0-SNAPSHOT/universalis-api-0.1.0-20170101.110000-2.jar");
        assertTrue("Artifact is also available with transformed versions",
                a1.isFile() && a2.isFile());
        assertThat(FileUtils.readFileToString(a1), equalTo("a"));
        assertThat(FileUtils.readFileToString(a2), equalTo("b"));
    }

}
