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
import java.util.Collections;

import org.apache.commons.io.FileUtils;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class AetherReleaseResolutionTest extends AetherResolutionSupport {

    @Test
    public void releaseIsAvailableInRemoteRepositoryNotUpdatingRelease() throws IOException {
        File differentLocalRepository = initFileRepository("dlr");
        File remoteRepository = initFileRepository("rr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.singletonList(remoteRepository))
                .withUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS)
                .build();

        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0", at("10:00"), "a");

        File file = resolver.download("io.fabric8.test/universalis-api/0.1.0");
        // first resolution
        assertThat(FileUtils.readFileToString(file), equalTo("a"));

        // don't do that, it's not proper use of maven. But sometimes we just have another deployment to public repository...
        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0", at("10:00"), "b");

        // second resolution
        file = resolver.download("io.fabric8.test/universalis-api/0.1.0");
        assertThat("Artifact won't be updated for release version",
                FileUtils.readFileToString(file), equalTo("a"));
    }

    @Test
    public void releaseIsAvailableInRemoteRepositoryUpdatingRelease() throws IOException {
        File differentLocalRepository = initFileRepository("dlr");
        File remoteRepository = initFileRepository("rr");
        MavenResolver resolver = new ResolverBuilder()
                .withRemoteRepositories(Collections.singletonList(remoteRepository))
                .withUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS)
                .withReleaseUpdates()
                .build();

        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0", at("10:00"), "a");

        File file = resolver.download("io.fabric8.test/universalis-api/0.1.0");
        // first resolution
        assertThat(FileUtils.readFileToString(file), equalTo("a"));

        // don't do that, it's not proper use of maven. But sometimes we just have another deployment to public repository...
        mvnDeploy(differentLocalRepository, remoteRepository, "io.fabric8.test", "universalis-api", "0.1.0", at("10:00"), "b");

        // second resolution
        file = resolver.download("io.fabric8.test/universalis-api/0.1.0");
        assertThat("Artifact will be updated though it's not proper usage of Maven",
                FileUtils.readFileToString(file), equalTo("b"));
    }

}
