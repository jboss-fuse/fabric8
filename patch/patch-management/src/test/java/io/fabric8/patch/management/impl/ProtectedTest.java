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
package io.fabric8.patch.management.impl;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.Utils;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProtectedTest {

    private File gitDir;
    private Git git = null;
    private BundleContext context;

    @Before
    public void init() throws IOException, GitAPIException {
        gitDir = new File("target/ProtectedTest");
        gitDir.mkdirs();
        FileUtils.deleteDirectory(gitDir);
        git = Git.init().setDirectory(this.gitDir).setGitDir(new File(this.gitDir, ".git")).call();
        git.commit()
                .setMessage("init")
                .setAuthor("me", "my@email").call();

        context = mock(BundleContext.class);
        Bundle b0 = mock(Bundle.class);
        when(context.getBundle(0)).thenReturn(b0);
        when(b0.getBundleContext()).thenReturn(context);
        when(context.getProperty("karaf.home")).thenReturn("target/ProtectedTest-karaf");
        when(context.getProperty("karaf.base")).thenReturn("target/ProtectedTest-karaf");
        when(context.getProperty("karaf.data")).thenReturn("target/ProtectedTest-karaf/data");
    }

    @Test
    public void updateBinAdminReferences() throws IOException {
        File binAdmin = new File(git.getRepository().getWorkTree(), "bin/admin");
        FileUtils.copyFile(new File("src/test/resources/files/bin/admin"), binAdmin);
        List<BundleUpdate> bundleUpdates = new LinkedList<>();
        bundleUpdates.add(BundleUpdate
                .from("mvn:org.apache.karaf.admin/org.apache.karaf.admin.command/2.4.0.redhat-620133")
                .to("mvn:org.apache.karaf.admin/org.apache.karaf.admin.command/2.4.0.redhat-620134"));
        bundleUpdates.add(BundleUpdate
                .from("mvn:org.apache.felix/org.apache.felix.gogo.runtime/0.12.1")
                .to("mvn:org.apache.felix/org.apache.felix.gogo.runtime/1.12.1"));

        Map<String, String> updates = Utils.collectLocationUpdates(bundleUpdates);
        new GitPatchManagementServiceImpl(context).updateReferences(git, "bin/admin", "system/", updates, false);

        String expected = FileUtils.readFileToString(new File("src/test/resources/files/bin/admin.updated"));
        String changed = FileUtils.readFileToString(binAdmin);
        assertThat(changed, equalTo(expected));
    }

    @Test
    public void updateBinAdminBatReferences() throws IOException {
        File binAdmin = new File(git.getRepository().getWorkTree(), "bin/admin.bat");
        FileUtils.copyFile(new File("src/test/resources/files/bin/admin.bat"), binAdmin);
        List<BundleUpdate> bundleUpdates = new LinkedList<>();
        bundleUpdates.add(BundleUpdate
                .from("mvn:org.apache.karaf.admin/org.apache.karaf.admin.core/2.4.0.redhat-620133")
                .to("mvn:org.apache.karaf.admin/org.apache.karaf.admin.core/2.4.0.redhat-630134"));

        Map<String, String> updates = Utils.collectLocationUpdates(bundleUpdates);
        new GitPatchManagementServiceImpl(context).updateReferences(git, "bin/admin.bat", "system/", updates, true);

        String expected = FileUtils.readFileToString(new File("src/test/resources/files/bin/admin.bat.updated"));
        String changed = FileUtils.readFileToString(binAdmin);
        assertThat(changed, equalTo(expected));
    }

    @Test
    public void updateEtcConfigReferences() throws IOException {
        File etcConfig = new File(git.getRepository().getWorkTree(), "etc/config.properties");
        FileUtils.copyFile(new File("src/test/resources/files/etc/config.properties"), etcConfig);
        List<BundleUpdate> bundleUpdates = new LinkedList<>();
        bundleUpdates.add(BundleUpdate
                .from("mvn:org.eclipse/osgi/3.9.1-v20140110-1610")
                .to("mvn:org.eclipse/osgi/3.9.1-v20151231-2359"));
        bundleUpdates.add(BundleUpdate
                .from("mvn:org.apache.felix/org.apache.felix.framework/4.4.1")
                .to("mvn:org.apache.felix/org.apache.felix.framework/4.4.2"));

        Map<String, String> updates = Utils.collectLocationUpdates(bundleUpdates);
        new GitPatchManagementServiceImpl(context).updateReferences(git, "etc/config.properties", "${karaf.default.repository}/", updates, false);

        String expected = FileUtils.readFileToString(new File("src/test/resources/files/etc/config.properties.updated"));
        String changed = FileUtils.readFileToString(etcConfig);
        assertThat(changed, equalTo(expected));
    }

    @Test
    public void updateEtcStartupReferences() throws IOException {
        File etcConfig = new File(git.getRepository().getWorkTree(), "etc/startup.properties");
        FileUtils.copyFile(new File("src/test/resources/files/etc/startup.properties"), etcConfig);
        List<BundleUpdate> bundleUpdates = new LinkedList<>();
        bundleUpdates.add(BundleUpdate
                .from("mvn:org.ops4j.pax.url/pax-url-wrap/2.4.0/jar/uber")
                .to("mvn:org.ops4j.pax.url/pax-url-wrap/2.4.2/jar/uber"));
        bundleUpdates.add(BundleUpdate
                .from("file:/opt/karaf/system/org/ops4j/pax/url/pax-url-aether/2.4.0/pax-url-aether-2.4.0.jar")
                .to("file:/opt/karaf/system/org/ops4j/pax/url/pax-url-aether/2.4.2/pax-url-aether-2.4.2.jar"));
        bundleUpdates.add(BundleUpdate
                .from("mvn:org.apache.felix/org.apache.felix.bundlerepository/2.0.4")
                .to("mvn:org.apache.felix/org.apache.felix.bundlerepository/2.1.4"));

        Map<String, String> updates = Utils.collectLocationUpdates(bundleUpdates);
        new GitPatchManagementServiceImpl(context).updateReferences(git, "etc/startup.properties", "", updates, false);

        String expected = FileUtils.readFileToString(new File("src/test/resources/files/etc/startup.properties.updated"));
        String changed = FileUtils.readFileToString(etcConfig);
        assertThat(changed, equalTo(expected));
    }

}
