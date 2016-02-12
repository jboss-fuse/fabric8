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
package io.fabric8.patch.management;

import java.io.File;
import java.io.IOException;
import java.util.List;

import io.fabric8.patch.management.impl.GitPatchManagementServiceImpl;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PatchManagementTest extends PatchTestSupport {

    private PatchManagement pm;

    @Before
    public void init() throws IOException, GitAPIException {
        super.init(true, true);

        pm = new GitPatchManagementServiceImpl(bundleContext);
        ((GitPatchManagementServiceImpl)pm).start();

        // prepare some ZIP patches
        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        preparePatchZip("src/test/resources/content/patch3", "target/karaf/patches/source/patch-3.zip", false);
    }

    @Test
    public void fetchPatch1FromZipFileWithDescriptor() throws IOException {
        List<PatchData> patches = pm.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        assertThat(patches.size(), equalTo(1));
        PatchData data = patches.get(0);
        assertThat(data.getId(), equalTo("my-patch-1"));
        assertThat(data.getBundles().size(), equalTo(1));
        assertThat(data.getBundles().iterator().next(), equalTo("mvn:io.fabric8/fabric-tranquility/1.2.3"));
        assertThat(data.getFiles().size(), equalTo(2));

        assertTrue(FileUtils.readFileToString(new File(karafHome, "patches/my-patch-1/bin/start")).contains("echo \"started\"\n"));
        assertThat("Fetching a patch should extract maven artifacts immediately",
                FileUtils.readFileToString(new File(karafHome, "system/io/fabric8/fabric-tranquility/1.2.3/fabric-tranquility-1.2.3.jar")),
                equalTo("JAR\n"));
    }

    @Test
    public void fetchPatch2AsPlainDescriptor() throws IOException {
        List<PatchData> patches = pm.fetchPatches(new File("src/test/resources/descriptors/my-patch-2.patch").toURI().toURL());
        assertThat(patches.size(), equalTo(1));
        PatchData data = patches.get(0);
        assertThat(data.getId(), equalTo("my-patch-2"));
        assertThat(data.getBundles().size(), equalTo(1));
        assertThat(data.getBundles().iterator().next(), equalTo("mvn:io.fabric8/fabric-tranquility/1.2.3"));
        assertThat(data.getFiles().size(), equalTo(0));

        assertFalse(new File(karafHome, "patches/my-patch-2").exists());
        assertTrue(new File(karafHome, "patches/my-patch-2.patch").exists());
        assertTrue(new File(karafHome, "patches/my-patch-2.patch").isFile());
    }

    @Test
    public void fetchPatch3FromZipFileWithoutDescriptor() throws IOException {
        List<PatchData> patches = pm.fetchPatches(new File("target/karaf/patches/source/patch-3.zip").toURI().toURL());
        assertThat(patches.size(), equalTo(1));
        PatchData data = patches.get(0);
        assertTrue(data.isGenerated());
        assertThat(data.getId(), equalTo("patch-3"));
        assertThat(data.getBundles().size(), equalTo(1));
        assertThat(data.getBundles().iterator().next(), equalTo("mvn:io.fabric8/fabric-colours/1.2.3"));
        assertThat(data.getFiles().size(), equalTo(2));

        assertTrue(FileUtils.readFileToString(new File(karafHome, "patches/patch-3/bin/stop")).contains("echo \"stopped\"\n"));
        assertThat("Fetching a patch should extract maven artifacts immediately",
                FileUtils.readFileToString(new File(karafHome, "system/io/fabric8/fabric-colours/1.2.3/fabric-colours-1.2.3.jar")),
                equalTo("JAR\n"));
        assertFalse("Maven artifacts should not be extracted to patch data directory", new File(karafHome, "patches/patch-3/system").exists());
    }

}
