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
package io.fabric8.patch.management;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.fabric8.patch.management.impl.GitPatchManagementService;
import io.fabric8.patch.management.impl.GitPatchManagementServiceImpl;
import io.fabric8.patch.management.impl.GitPatchRepository;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.startlevel.BundleStartLevel;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class GitPatchManagementServiceForStandaloneChildContainersTest extends PatchTestSupport {

    private GitPatchManagementService pm;
    private BundleStartLevel bsl;

    @Before
    public void init() throws IOException, GitAPIException {
        super.init(true, true);

        bsl = mock(BundleStartLevel.class);
        when(bundle.adapt(BundleStartLevel.class)).thenReturn(bsl);

        when(systemContext.getDataFile("patches")).thenReturn(new File(karafHome, "data/cache/bundle0/data/patches"));

        // root container's part - initialization of baselines
        freshKarafStandaloneDistro();
        FileUtils.copyFile(new File("src/test/resources/karaf2/system/org/apache/karaf/admin/org.apache.karaf.admin.core/2.4.0.redhat-620133/org.apache.karaf.admin.core-2.4.0.redhat-620133.jar"),
                new File(karafHome, "system/org/apache/karaf/admin/org.apache.karaf.admin.core/2.4.0.redhat-620133/org.apache.karaf.admin.core-2.4.0.redhat-620133.jar"));
        preparePatchZip("src/test/resources/baselines/baseline3", "target/karaf/patches/jboss-fuse-karaf-6.2.0-baseline.zip", true);

        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        pm.ensurePatchManagementInitialized();

        karafHome = new File("target/karaf");
        karafBase = new File("target/karaf/instances/child");
        super.init(false, false);

        properties.setProperty("karaf.name", "child");
        properties.setProperty("karaf.instances", properties.getProperty("karaf.home") + "/instances");
    }

    @After
    public void stop() {
        pm.stop();
    }

    @Test
    public void initializationPerformedBaselineDistributionFoundInPatches() throws IOException, GitAPIException {
        freshKarafChildDistro();
        validateInitialGitRepository();
    }

    @Test
    public void installPPatchAndThenRPatch() throws IOException, GitAPIException {
        initializationPerformedBaselineDistributionFoundInPatches();

        // prepare some ZIP patches
        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        preparePatchZip("src/test/resources/content/patch7", "target/karaf/patches/source/patch-7.zip", false);

        PatchManagement service = (PatchManagement) pm;
        PatchData patchData1 = service.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL()).get(0);
        Patch patch1 = service.trackPatch(patchData1);

        String tx = service.beginInstallation(PatchKind.NON_ROLLUP);
        service.install(tx, patch1, null);
        service.commitInstallation(tx);

        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();
        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);

        assertTrue(repository.containsTag(fork, "patch-my-patch-1-child"));
        // tag from baseline
        assertTrue(repository.containsTag(fork, "baseline-child-2.4.0.redhat-620133"));
        // tag from patch (root and child) - not available yet
        assertFalse(repository.containsTag(fork, "baseline-6.2.0.redhat-002"));
        assertFalse(repository.containsTag(fork, "baseline-child-2.4.0.redhat-621084"));
        assertFalse(repository.containsTag(fork, "baseline-child-2.4.0.redhat-621084-child"));

        repository.closeRepository(fork, true);

        PatchData patchData7 = service.fetchPatches(new File("target/karaf/patches/source/patch-7.zip").toURI().toURL()).get(0);
        Patch patch7 = service.trackPatch(patchData7);

        tx = service.beginInstallation(PatchKind.ROLLUP);
        service.install(tx, patch7, null);
        service.commitInstallation(tx);

        fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        assertFalse(repository.containsTag(fork, "patch-my-patch-1-child"));
        // there's no such tag - we're not in STANDALONE container
        assertFalse(repository.containsTag(fork, "baseline-6.2.0.redhat-002"));
        assertTrue(repository.containsTag(fork, "baseline-child-2.4.0.redhat-621084"));

        repository.closeRepository(fork, true);
    }

    @Test
    public void installRollupPatch() throws IOException, GitAPIException {
        initializationPerformedBaselineDistributionFoundInPatches();
        freshKarafStandaloneDistro();
        PatchManagement management = (PatchManagement) pm;
        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        repository.prepareCommit(fork, "artificial change, not treated as user change (could be a patch)").call();
        repository.prepareCommit(fork, "artificial change, not treated as user change").call();
        ((GitPatchManagementServiceImpl)pm).applyUserChanges(fork); // no changes, but commit
        FileUtils.write(new File(karafBase, "bin/start"), "echo \"another user change\"\n", true);
        ((GitPatchManagementServiceImpl)pm).applyUserChanges(fork); // conflicting change, but commit
        FileUtils.write(new File(karafBase, "bin/test"), "echo \"another user change\"\n");
        ((GitPatchManagementServiceImpl)pm).applyUserChanges(fork); // non-conflicting
        repository.closeRepository(fork, true);

        preparePatchZip("src/test/resources/content/patch7", "target/karaf/patches/source/patch-7.zip", false);
        List<PatchData> patches = management.fetchPatches(new File("target/karaf/patches/source/patch-7.zip").toURI().toURL());
        Patch patch = management.trackPatch(patches.get(0));

        String tx = management.beginInstallation(PatchKind.ROLLUP);
        management.install(tx, patch, null);

        @SuppressWarnings("unchecked")
        Map<String, Git> transactions = (Map<String, Git>) getField(management, "pendingTransactions");
        assertThat(transactions.size(), equalTo(1));
        fork = transactions.values().iterator().next();

        ObjectId since = fork.getRepository().resolve("baseline-child-2.4.0.redhat-620133^{commit}");
        ObjectId to = fork.getRepository().resolve(tx);
        Iterable<RevCommit> commits = fork.log().addRange(since, to).call();
        // only one "user change", because we had two conflicts with new baseline - they were resolved
        // by picking what already comes from rollup patch ("ours"):
        /*
         * Problem with applying the change 657f11c4b65bb7893a2b82f888bb9731a6d5f7d0:
         *  - bin/start: BOTH_MODIFIED
         * Choosing "ours" change
         * Problem with applying the change d9272b97582582f4b056f7170130ec91fc21aeac:
         *  - bin/start: BOTH_MODIFIED
         * Choosing "ours" change
         */
        List<String> commitList = Arrays.asList(
                "[PATCH] Apply user changes",
                "[PATCH] Apply user changes",
                "[PATCH] Apply user changes",
                "[PATCH] Rollup patch patch-7 - resetting etc/overrides.properties",
                "[PATCH/baseline] Installing baseline-child-2.4.0.redhat-621084");

        int n = 0;
        for (RevCommit c : commits) {
            String msg = c.getShortMessage();
            assertThat(msg, equalTo(commitList.get(n++)));
        }

        assertThat(n, equalTo(commitList.size()));

        assertThat(fork.tagList().call().size(), equalTo(4));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
        assertFalse(repository.containsTag(fork, "baseline-6.2.0.redhat-002"));
        assertTrue(repository.containsTag(fork, "baseline-child-2.4.0.redhat-620133"));
        assertTrue(repository.containsTag(fork, "baseline-child-2.4.0.redhat-621084"));
    }

    @Test
    public void rollbackInstalledRollupPatch() throws IOException, GitAPIException {
        initializationPerformedBaselineDistributionFoundInPatches();
        freshKarafStandaloneDistro();
        PatchManagement management = (PatchManagement) pm;
        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();

        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        preparePatchZip("src/test/resources/content/patch7", "target/karaf/patches/source/patch-7.zip", false);

        List<PatchData> patches = management.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        Patch patch1 = management.trackPatch(patches.get(0));
        patches = management.fetchPatches(new File("target/karaf/patches/source/patch-7.zip").toURI().toURL());
        Patch patch7 = management.trackPatch(patches.get(0));

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master1 = fork.getRepository().resolve(GitPatchRepository.ADMIN_HISTORY_BRANCH + "-child");

        String tx = management.beginInstallation(PatchKind.ROLLUP);
        management.install(tx, patch7, null);
        management.commitInstallation(tx);

        // install P patch to check if rolling back rollup patch will remove P patch's tag
        tx = management.beginInstallation(PatchKind.NON_ROLLUP);
        management.install(tx, patch1, null);
        management.commitInstallation(tx);

        fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        assertFalse(repository.containsTag(fork, "patch-my-patch-1"));
        assertTrue(repository.containsTag(fork, "patch-my-patch-1-child"));
        assertTrue(repository.containsTag(fork, "baseline-child-2.4.0.redhat-620133"));
        assertTrue(repository.containsTag(fork, "baseline-child-2.4.0.redhat-621084"));

        management.rollback(patch7.getPatchData());

        repository.closeRepository(fork, true);
        fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master2 = fork.getRepository().resolve(GitPatchRepository.ADMIN_HISTORY_BRANCH + "-child");

        assertThat(master1, not(equalTo(master2)));
        assertThat(fork.tagList().call().size(), equalTo(4));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertFalse("P patch1 should be not visible as installed", repository.containsTag(fork, "patch-my-patch-1-child"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
        assertTrue(repository.containsTag(fork, "baseline-child-2.4.0.redhat-620133"));
        assertTrue(repository.containsTag(fork, "baseline-child-2.4.0.redhat-621084"));
        assertFalse(repository.containsTag(fork, "baseline-child-2.4.0.redhat-621084-child"));
        assertFalse("When rolling back rollup patch, newer P patches' tags should be removed",
                repository.containsTag(fork, "patch-my-patch-1"));
        assertThat(repository.findCurrentBaseline(fork).getTagName(), equalTo("baseline-child-2.4.0.redhat-620133"));
    }

    @Test
    public void installNonRollupPatch() throws IOException, GitAPIException {
        initializationPerformedBaselineDistributionFoundInPatches();
        freshKarafStandaloneDistro();
        PatchManagement management = (PatchManagement) pm;
        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ((GitPatchManagementServiceImpl)pm).applyUserChanges(fork); // no changes, but commit
        repository.prepareCommit(fork, "artificial change, not treated as user change (could be a patch)").call();
        repository.push(fork);
        FileUtils.write(new File(karafBase, "bin/shutdown"), "#!/bin/bash\nexit 42");
        ((GitPatchManagementServiceImpl)pm).applyUserChanges(fork);
        repository.closeRepository(fork, true);

        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        List<PatchData> patches = management.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        Patch patch = management.trackPatch(patches.get(0));

        String tx = management.beginInstallation(PatchKind.NON_ROLLUP);
        management.install(tx, patch, null);

        @SuppressWarnings("unchecked")
        Map<String, Git> transactions = (Map<String, Git>) getField(management, "pendingTransactions");
        assertThat(transactions.size(), equalTo(1));
        fork = transactions.values().iterator().next();

        ObjectId since = fork.getRepository().resolve("baseline-child-2.4.0.redhat-620133^{commit}");
        ObjectId to = fork.getRepository().resolve(tx);
        Iterable<RevCommit> commits = fork.log().addRange(since, to).call();
        List<String> commitList = Arrays.asList(
                "[PATCH] Installing patch my-patch-1",
                "[PATCH] Apply user changes",
                "artificial change, not treated as user change (could be a patch)",
                "[PATCH] Apply user changes");

        int n = 0;
        for (RevCommit c : commits) {
            String msg = c.getShortMessage();
            assertThat(msg, equalTo(commitList.get(n++)));
        }

        assertThat(n, equalTo(commitList.size()));

        assertThat(fork.tagList().call().size(), equalTo(4));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
        assertTrue(repository.containsTag(fork, "baseline-child-2.4.0.redhat-620133"));
        assertFalse(repository.containsTag(fork, "patch-my-patch-1"));
        assertTrue(repository.containsTag(fork, "patch-my-patch-1-child"));

        assertThat("The conflict should be resolved in special way", FileUtils.readFileToString(new File(karafHome, "bin/setenv")),
                equalTo("JAVA_MIN_MEM=2G # Minimum memory for the JVM\n"));
    }

    @Test
    public void rollbackInstalledNonRollupPatch() throws IOException, GitAPIException {
        initializationPerformedBaselineDistributionFoundInPatches();
        freshKarafStandaloneDistro();
        PatchManagement management = (PatchManagement) pm;
        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();

        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        List<PatchData> patches = management.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        Patch patch = management.trackPatch(patches.get(0));

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master1 = fork.getRepository().resolve(GitPatchRepository.ADMIN_HISTORY_BRANCH + "-child");

        String tx = management.beginInstallation(PatchKind.NON_ROLLUP);
        management.install(tx, patch, null);
        management.commitInstallation(tx);

        fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        assertTrue(repository.containsTag(fork, "patch-my-patch-1-child"));
        repository.closeRepository(fork, true);

        management.rollback(patch.getPatchData());

        repository.closeRepository(fork, true);
        fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master2 = fork.getRepository().resolve(GitPatchRepository.ADMIN_HISTORY_BRANCH + "-child");

        assertThat(master1, not(equalTo(master2)));
        assertThat(fork.tagList().call().size(), equalTo(3));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
        assertTrue(repository.containsTag(fork, "baseline-child-2.4.0.redhat-620133"));
        assertFalse(repository.containsTag(fork, "patch-my-patch-1-child"));

        String binStart = FileUtils.readFileToString(new File(karafHome, "bin/start"));
        assertTrue("bin/start should be at previous version", binStart.contains("echo \"This is user's change\""));
    }

    /**
     * Create crucial Karaf files (like etc/startup.properties)
     */
    private void freshKarafChildDistro() throws IOException {
        FileUtils.copyFile(new File("src/test/resources/karaf2/etc/startup.properties"), new File(karafBase, "etc/startup.properties"));
        FileUtils.copyFile(new File("src/test/resources/karaf2/etc/system.properties"), new File(karafBase, "etc/system.properties"));
        FileUtils.copyFile(new File("src/test/resources/karaf2/bin/start"), new File(karafBase, "bin/start"));
        FileUtils.copyFile(new File("src/test/resources/karaf2/bin/stop"), new File(karafBase, "bin/stop"));
        FileUtils.copyFile(new File("src/test/resources/karaf2/lib/karaf.jar"), new File(karafHome, "lib/karaf.jar"));
        FileUtils.copyFile(new File("src/test/resources/karaf2/fabric/import/fabric/profiles/default.profile/io.fabric8.version.properties"),
                new File(karafHome, "fabric/import/fabric/profiles/default.profile/io.fabric8.version.properties"));
    }

    private void validateInitialGitRepository() throws IOException, GitAPIException {
        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        pm.ensurePatchManagementInitialized();
        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();

        verify(bsl, atLeastOnce()).setStartLevel(2);

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        List<Ref> tags = fork.tagList().call();
        boolean found1 = false;
        boolean found2 = false;
        for (Ref tag : tags) {
            if ("refs/tags/baseline-6.2.0".equals(tag.getName())) {
                found1 = true;
            }
            if ("refs/tags/baseline-child-2.4.0.redhat-620133".equals(tag.getName())) {
                found2 = true;
            }
        }
        assertTrue("Repository should contain baseline tags for version 6.2.0 (both for root and admin:create based child containrs)",
                found1 && found2);

        repository.closeRepository(fork, true);
    }

}
