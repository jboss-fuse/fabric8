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
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.fabric8.patch.management.impl.GitPatchManagementService;
import io.fabric8.patch.management.impl.GitPatchManagementServiceImpl;
import io.fabric8.patch.management.impl.GitPatchRepository;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.startlevel.BundleStartLevel;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GitPatchManagementServiceTest extends PatchTestSupport {

    private GitPatchManagementService pm;
    private BundleStartLevel bsl;

    @Before
    public void init() throws IOException, GitAPIException {
        super.init(true, true);

        bsl = mock(BundleStartLevel.class);
        when(bundle.adapt(BundleStartLevel.class)).thenReturn(bsl);

        when(systemContext.getDataFile("patches")).thenReturn(new File(karafHome, "data/cache/bundle0/data/patches"));
    }

    @Test
    public void disabledPatchManagement() throws IOException, GitAPIException {
        System.setProperty("patching.disabled", "true");
        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        assertFalse(pm.isEnabled());
        System.setProperty("patching.disabled", "");
    }

    @Test
    public void enabledPatchManagement() throws IOException, GitAPIException {
        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        assertTrue(pm.isEnabled());
    }

    @Test
    public void initializationPerformedNoFuseVersion() throws IOException, GitAPIException {
        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        try {
            pm.ensurePatchManagementInitialized();
            fail("Should fail, because versions can't be determined");
        } catch (PatchException e) {
            assertTrue(e.getMessage().contains("Can't find"));
        }
    }

    @Test
    public void initializationPerformedNoBaselineDistribution() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        try {
            pm.ensurePatchManagementInitialized();
            fail("Should fail, because no baseline distribution is found");
        } catch (PatchException e) {
            assertTrue(e.getMessage().contains("Can't find baseline distribution"));
        }
    }

    @Test
    public void initializationPerformedBaselineDistributionFoundInPatches() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        preparePatchZip("src/test/resources/baselines/baseline1", "target/karaf/patches/jboss-fuse-full-6.2.0-baseline.zip", true);
        validateInitialGitRepository();
        // check one more time - should not do anything harmful
        validateInitialGitRepository();
    }

    @Test
    public void initializationPerformedBaselineDistributionFoundInSystem() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        preparePatchZip("src/test/resources/baselines/baseline1", "target/karaf/system/org/jboss/fuse/jboss-fuse-full/6.2.0/jboss-fuse-full-6.2.0-baseline.zip", true);
        validateInitialGitRepository();
    }

    @Test
    public void initializationPerformedPatchManagementAlreadyInstalled() throws IOException, GitAPIException {
        testWithAlreadyInstalledPatchManagementBundle("1.2.0");
    }

    @Test
    public void initializationPerformedPatchManagementInstalledAtOlderVersion() throws IOException, GitAPIException {
        testWithAlreadyInstalledPatchManagementBundle("1.1.9");
    }

    private void testWithAlreadyInstalledPatchManagementBundle(String version) throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        String line = String.format("io/fabric8/patch/patch-management/%s/patch-management-%s.jar=2\n", version, version);
        FileUtils.write(new File(karafHome, "etc/startup.properties"), line, true);
        preparePatchZip("src/test/resources/baselines/baseline1", "target/karaf/system/org/jboss/fuse/jboss-fuse-full/6.2.0/jboss-fuse-full-6.2.0-baseline.zip", true);
        validateInitialGitRepository();
    }

    /**
     * Patch 1 is non-rollup patch
     * @throws IOException
     * @throws GitAPIException
     */
    @Test
    public void addPatch1() throws IOException, GitAPIException {
        initializationPerformedBaselineDistributionFoundInSystem();

        // prepare some ZIP patches
        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);

        PatchManagement service = (PatchManagement) pm;
        PatchData patchData = service.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL()).get(0);
        assertThat(patchData.getId(), equalTo("my-patch-1"));
        Patch patch = service.trackPatch(patchData);

        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();
        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);

        // we should see remote branch for the patch, but without checking it out, it won't be available in the clone's local branches
        List<Ref> branches = fork.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
        Ref patchBranch = null;
        for (Ref remoteBranch : branches) {
            if (String.format("refs/remotes/origin/patch-%s", patchData.getId()).equals(remoteBranch.getName())) {
                patchBranch = remoteBranch;
                break;
            }
        }
        assertNotNull("Should find remote branch for the added patch", patchBranch);

        assertThat(patch.getManagedPatch().getCommitId(), equalTo(patchBranch.getObjectId().getName()));

        RevCommit patchCommit = new RevWalk(fork.getRepository()).parseCommit(patchBranch.getObjectId());
        // patch commit should be child of baseline commit
        RevCommit baselineCommit = new RevWalk(fork.getRepository()).parseCommit(patchCommit.getParent(0));

        // this baseline commit should be tagged "baseline-VERSION"
        Ref tag = fork.tagList().call().get(0);
        assertThat(tag.getName(), equalTo("refs/tags/baseline-6.2.0"));
        RevCommit baselineCommitFromTag = new RevWalk(fork.getRepository()).parseCommit(tag.getTarget().getObjectId());
        assertThat(baselineCommit.getId(), equalTo(baselineCommitFromTag.getId()));

        // let's see the patch applied to baseline-6.2.0
        fork.checkout()
                .setName("my-patch-1")
                .setStartPoint("origin/patch-my-patch-1")
                .setCreateBranch(true)
                .call();
        String myProperties = FileUtils.readFileToString(new File(fork.getRepository().getWorkTree(), "etc/my.properties"));
        assertTrue(myProperties.contains("p1 = v1"));

        repository.closeRepository(fork, true);
    }

    @Test
    public void installThreeNonRollupPatches() throws IOException, GitAPIException {
        initializationPerformedBaselineDistributionFoundInSystem();

        // prepare some ZIP patches
        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        preparePatchZip("src/test/resources/content/patch5", "target/karaf/patches/source/patch-5.zip", false);
        preparePatchZip("src/test/resources/content/patch6", "target/karaf/patches/source/patch-6.zip", false);

        PatchManagement service = (PatchManagement) pm;
        PatchData patchData1 = service.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL()).get(0);
        Patch patch1 = service.trackPatch(patchData1);
        PatchData patchData5 = service.fetchPatches(new File("target/karaf/patches/source/patch-5.zip").toURI().toURL()).get(0);
        Patch patch5 = service.trackPatch(patchData5);
        PatchData patchData6 = service.fetchPatches(new File("target/karaf/patches/source/patch-6.zip").toURI().toURL()).get(0);
        Patch patch6 = service.trackPatch(patchData6);

        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();
        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);

        String tx = service.beginInstallation(PatchKind.NON_ROLLUP);

        List<BundleUpdate> patch1Updates = new LinkedList<>();
        patch1Updates.add(BundleUpdate.from("mvn:io.fabric8/fabric-tranquility/1.2.0")
                .to("mvn:io.fabric8/fabric-tranquility/1.2.3"));
        service.install(tx, patch1, patch1Updates);

        List<BundleUpdate> patch5Updates = new LinkedList<>();
        patch5Updates.add(BundleUpdate.from("mvn:io.fabric8/fabric-zen/1.1.44/war")
                .to("mvn:io.fabric8/fabric-zen/1.2.0/war"));
        service.install(tx, patch5, patch5Updates);

        List<BundleUpdate> patch6Updates = new LinkedList<>();
        patch5Updates.add(BundleUpdate.from("mvn:io.fabric8/fabric-zen/1.2.4/war")
                .to("mvn:io.fabric8/fabric-zen/1.3.0/war"));
        service.install(tx, patch6, patch6Updates);

        service.commitInstallation(tx);

        String binAdmin = FileUtils.readFileToString(new File(karafHome, "bin/admin"));
        assertTrue(binAdmin.contains("system/io/fabric8/fabric-tranquility/1.2.3/fabric-tranquility-1.2.3.jar"));

        String etcStartupProperties = FileUtils.readFileToString(new File(karafHome, "etc/startup.properties"));
        // version from patch-5 should be chosen, because there's 1.1.44->1.2.0
        assertTrue(etcStartupProperties.contains("io/fabric8/fabric-zen/1.2.0/fabric-zen-1.2.0.war=42"));
        assertTrue(etcStartupProperties.contains("io/fabric8/fabric-tranquility/1.2.3/fabric-tranquility-1.2.3.jar=42"));

        String etcOverridesProperties = FileUtils.readFileToString(new File(karafHome, "etc/overrides.properties"));
        assertTrue(etcOverridesProperties.contains("mvn:io.fabric8/fabric-tranquility/1.2.3\n"));
        assertTrue(etcOverridesProperties.contains("mvn:io.fabric8/fabric-zen/1.2.0/war;range=[1.1,1.2)\n"));
        assertTrue(etcOverridesProperties.contains("mvn:io.fabric8/fabric-zen/1.3.3/war\n"));

        /* rollback time! */

        Patch p5 = service.loadPatch(new PatchDetailsRequest("my-patch-5"));
        service.rollback(p5.getPatchData());

        binAdmin = FileUtils.readFileToString(new File(karafHome, "bin/admin"));
        assertTrue(binAdmin.contains("system/io/fabric8/fabric-tranquility/1.2.3/fabric-tranquility-1.2.3.jar"));

        etcStartupProperties = FileUtils.readFileToString(new File(karafHome, "etc/startup.properties"));
        // rollback wasn't successful
        assertTrue(etcStartupProperties.contains("io/fabric8/fabric-zen/1.2.0/fabric-zen-1.2.0.war=42"));
        assertFalse(etcStartupProperties.contains("io/fabric8/fabric-zen/1.1.44/fabric-zen-1.1.44.war=42"));
        assertTrue(etcStartupProperties.contains("io/fabric8/fabric-tranquility/1.2.3/fabric-tranquility-1.2.3.jar=42"));

        etcOverridesProperties = FileUtils.readFileToString(new File(karafHome, "etc/overrides.properties"));
        assertTrue(etcOverridesProperties.contains("mvn:io.fabric8/fabric-tranquility/1.2.3\n"));
        assertTrue(etcOverridesProperties.contains("mvn:io.fabric8/fabric-zen/1.2.0/war;range=[1.1,1.2)\n"));
        assertTrue(etcOverridesProperties.contains("mvn:io.fabric8/fabric-zen/1.3.3/war\n"));

        Patch p6 = service.loadPatch(new PatchDetailsRequest("my-patch-6"));
        service.rollback(p6.getPatchData());

        binAdmin = FileUtils.readFileToString(new File(karafHome, "bin/admin"));
        assertTrue(binAdmin.contains("system/io/fabric8/fabric-tranquility/1.2.3/fabric-tranquility-1.2.3.jar"));

        etcStartupProperties = FileUtils.readFileToString(new File(karafHome, "etc/startup.properties"));
        assertTrue(etcStartupProperties.contains("io/fabric8/fabric-zen/1.2.0/fabric-zen-1.2.0.war=42"));
        assertTrue(etcStartupProperties.contains("io/fabric8/fabric-tranquility/1.2.3/fabric-tranquility-1.2.3.jar=42"));

        etcOverridesProperties = FileUtils.readFileToString(new File(karafHome, "etc/overrides.properties"));
        assertFalse(etcOverridesProperties.contains("mvn:io.fabric8/fabric-zen/1.3.3/war\n"));

        repository.closeRepository(fork, true);
    }

    @Test
    public void installPPatchAndThenRPatch() throws IOException, GitAPIException {
        initializationPerformedBaselineDistributionFoundInSystem();

        // prepare some ZIP patches
        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        preparePatchZip("src/test/resources/content/patch4", "target/karaf/patches/source/patch-4.zip", false);

        PatchManagement service = (PatchManagement) pm;
        PatchData patchData1 = service.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL()).get(0);
        Patch patch1 = service.trackPatch(patchData1);
        PatchData patchData4 = service.fetchPatches(new File("target/karaf/patches/source/patch-4.zip").toURI().toURL()).get(0);
        Patch patch4 = service.trackPatch(patchData4);

        String tx = service.beginInstallation(PatchKind.NON_ROLLUP);
        service.install(tx, patch1, null);
        service.commitInstallation(tx);

        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();
        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);

        assertTrue(repository.containsTag(fork, "patch-my-patch-1"));
        assertFalse(repository.containsTag(fork, "baseline-6.2.0.redhat-002"));

        repository.closeRepository(fork, true);

        tx = service.beginInstallation(PatchKind.ROLLUP);
        service.install(tx, patch4, null);
        service.commitInstallation(tx);

        fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        assertFalse(repository.containsTag(fork, "patch-my-patch-1"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0.redhat-002"));

        repository.closeRepository(fork, true);
    }

    /**
     * Patch 4 is rollup patch (doesn't contain descriptor, contains default.profile/io.fabric8.version.properties)
     * Adding it is not different that adding non-rollup patch. Installation is different
     * @throws IOException
     * @throws GitAPIException
     */
    @Test
    public void addPatch4() throws IOException, GitAPIException {
        initializationPerformedBaselineDistributionFoundInSystem();

        // prepare some ZIP patches
        preparePatchZip("src/test/resources/content/patch4", "target/karaf/patches/source/patch-4.zip", false);

        PatchManagement service = (PatchManagement) pm;
        PatchData patchData = service.fetchPatches(new File("target/karaf/patches/source/patch-4.zip").toURI().toURL()).get(0);
        assertThat(patchData.getId(), equalTo("patch-4"));
        Patch patch = service.trackPatch(patchData);

        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();
        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);

        // we should see remote branch for the patch, but without checking it out, it won't be available in the clone's local branches
        List<Ref> branches = fork.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
        Ref patchBranch = null;
        for (Ref remoteBranch : branches) {
            if (String.format("refs/remotes/origin/patch-%s", patchData.getId()).equals(remoteBranch.getName())) {
                patchBranch = remoteBranch;
                break;
            }
        }
        assertNotNull("Should find remote branch for the added patch", patchBranch);

        assertThat(patch.getManagedPatch().getCommitId(), equalTo(patchBranch.getObjectId().getName()));

        RevCommit patchCommit = new RevWalk(fork.getRepository()).parseCommit(patchBranch.getObjectId());
        // patch commit should be child of baseline commit
        RevCommit baselineCommit = new RevWalk(fork.getRepository()).parseCommit(patchCommit.getParent(0));

        // this baseline commit should be tagged "baseline-VERSION"
        Ref tag = fork.tagList().call().get(0);
        assertThat(tag.getName(), equalTo("refs/tags/baseline-6.2.0"));
        RevCommit baselineCommitFromTag = new RevWalk(fork.getRepository()).parseCommit(tag.getTarget().getObjectId());
        assertThat(baselineCommit.getId(), equalTo(baselineCommitFromTag.getId()));

        List<DiffEntry> patchDiff = repository.diff(fork, baselineCommit, patchCommit);
        assertThat("patch-4 should lead to 8 changes", patchDiff.size(), equalTo(9));
        for (Iterator<DiffEntry> iterator = patchDiff.iterator(); iterator.hasNext(); ) {
            DiffEntry de = iterator.next();
            if ("bin/start".equals(de.getNewPath()) && de.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                iterator.remove();
            }
            if ("bin/stop".equals(de.getNewPath()) && de.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                iterator.remove();
            }
            if ("bin/setenv".equals(de.getNewPath()) && de.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                iterator.remove();
            }
            if ("etc/startup.properties".equals(de.getNewPath()) && de.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                iterator.remove();
            }
            if ("etc/my.properties".equals(de.getNewPath()) && de.getChangeType() == DiffEntry.ChangeType.ADD) {
                iterator.remove();
            }
            if ("etc/system.properties".equals(de.getNewPath()) && de.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                iterator.remove();
            }
            if ("fabric/import/fabric/profiles/default.profile/io.fabric8.agent.properties".equals(de.getNewPath()) && de.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                iterator.remove();
            }
            if ("fabric/import/fabric/profiles/default.profile/io.fabric8.version.properties".equals(de.getNewPath()) && de.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                iterator.remove();
            }
            if ("patch-info.txt".equals(de.getNewPath()) && de.getChangeType() == DiffEntry.ChangeType.ADD) {
                iterator.remove();
            }
        }

        assertThat("Unknown changes in patch-4", patchDiff.size(), equalTo(0));

        // let's see the patch applied to baseline-6.2.0
        fork.checkout()
                .setName("patch-4")
                .setStartPoint("origin/patch-patch-4")
                .setCreateBranch(true)
                .call();
        String startupProperties = FileUtils.readFileToString(new File(fork.getRepository().getWorkTree(), "etc/startup.properties"));
        assertTrue(startupProperties.contains("org/ops4j/pax/url/pax-url-gopher/2.4.0/pax-url-gopher-2.4.0.jar=5"));

        repository.closeRepository(fork, true);
    }

    @Test
    public void listNoPatchesAvailable() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;
        assertThat(management.listPatches(false).size(), equalTo(0));
    }

    @Test
    public void listSingleUntrackedPatch() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;
        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        management.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        List<Patch> patches = management.listPatches(true);
        assertThat(patches.size(), equalTo(1));

        Patch p = patches.get(0);
        assertNotNull(p.getPatchData());
        assertNull(p.getResult());
        assertNull(p.getManagedPatch());

        assertThat(p.getPatchData().getId(), equalTo("my-patch-1"));
        assertThat(p.getPatchData().getFiles().size(), equalTo(2));
        assertThat(p.getPatchData().getBundles().size(), equalTo(1));
        assertThat(p.getPatchData().getBundles().iterator().next(), equalTo("mvn:io.fabric8/fabric-tranquility/1.2.3"));
    }

    @Test
    public void listSingleTrackedPatch() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;
        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        management.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        List<Patch> patches = management.listPatches(true);
        assertThat(patches.size(), equalTo(1));

        Patch p = patches.get(0);
        assertNotNull(p.getPatchData());
        assertNull(p.getResult());
        assertNull(p.getManagedPatch());

        ((PatchManagement) pm).trackPatch(p.getPatchData());

        p = management.listPatches(true).get(0);
        assertNotNull(p.getPatchData());
        assertNull(p.getResult());
        assertNotNull(p.getManagedPatch());

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        Ref ref = fork.checkout()
                .setCreateBranch(true)
                .setName("patch-my-patch-1")
                .setStartPoint("refs/remotes/origin/patch-my-patch-1")
                .call();

        // commit stored in ManagedPatch vs. commit of the patch branch
        assertThat(ref.getObjectId().getName(), equalTo(p.getManagedPatch().getCommitId()));
    }

    @Test
    public void listPatches() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;

        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        preparePatchZip("src/test/resources/content/patch3", "target/karaf/patches/source/patch-3.zip", false);

        // with descriptor
        management.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        // descriptor only
        management.fetchPatches(new File("src/test/resources/descriptors/my-patch-2.patch").toURI().toURL());
        // without descriptor
        management.fetchPatches(new File("target/karaf/patches/source/patch-3.zip").toURI().toURL());

        assertThat(management.listPatches(false).size(), equalTo(3));

        assertTrue(new File(patchesHome, "my-patch-1").isDirectory());
        assertTrue(new File(patchesHome, "my-patch-1.patch").isFile());
        assertFalse(new File(patchesHome, "my-patch-2").exists());
        assertTrue(new File(patchesHome, "my-patch-2.patch").isFile());
        assertTrue(new File(patchesHome, "patch-3").isDirectory());
        assertTrue(new File(patchesHome, "patch-3.patch").isFile());
    }

    @Test
    public void beginRollupPatchInstallation() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;
        String tx = management.beginInstallation(PatchKind.ROLLUP);
        assertTrue(tx.startsWith("refs/heads/patch-install-"));

        @SuppressWarnings("unchecked")
        Map<String, Git> transactions = (Map<String, Git>) getField(management, "pendingTransactions");
        assertThat(transactions.size(), equalTo(1));
        Git fork = transactions.values().iterator().next();
        ObjectId currentBranch = fork.getRepository().resolve("HEAD^{commit}");
        ObjectId tempBranch = fork.getRepository().resolve(tx + "^{commit}");
        ObjectId masterBranch = fork.getRepository().resolve("master^{commit}");
        ObjectId baseline = fork.getRepository().resolve("refs/tags/baseline-6.2.0^{commit}");
        assertThat(tempBranch, equalTo(currentBranch));
        assertThat(tempBranch, equalTo(baseline));
        assertThat(masterBranch, not(equalTo(currentBranch)));
    }

    @Test
    public void beginNonRollupPatchInstallation() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;
        String tx = management.beginInstallation(PatchKind.NON_ROLLUP);
        assertTrue(tx.startsWith("refs/heads/patch-install-"));

        @SuppressWarnings("unchecked")
        Map<String, Git> transactions = (Map<String, Git>) getField(management, "pendingTransactions");
        assertThat(transactions.size(), equalTo(1));
        Git fork = transactions.values().iterator().next();
        ObjectId currentBranch = fork.getRepository().resolve("HEAD^{commit}");
        ObjectId tempBranch = fork.getRepository().resolve(tx + "^{commit}");
        ObjectId masterBranch = fork.getRepository().resolve(GitPatchRepository.HISTORY_BRANCH + "^{commit}");
        ObjectId baseline = fork.getRepository().resolve("refs/tags/baseline-6.2.0^{commit}");
        assertThat(tempBranch, equalTo(currentBranch));
        assertThat(tempBranch, not(equalTo(baseline)));
        assertThat(masterBranch, equalTo(currentBranch));
    }

    @Test
    public void installRollupPatch() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        repository.prepareCommit(fork, "artificial change, not treated as user change (could be a patch)").call();
        repository.prepareCommit(fork, "artificial change, not treated as user change").call();
        ((GitPatchManagementServiceImpl)pm).applyUserChanges(fork); // no changes, but commit
        FileUtils.write(new File(karafHome, "bin/start"), "echo \"another user change\"\n", true);
        ((GitPatchManagementServiceImpl)pm).applyUserChanges(fork); // conflicting change, but commit
        FileUtils.write(new File(karafHome, "bin/test"), "echo \"another user change\"\n");
        ((GitPatchManagementServiceImpl)pm).applyUserChanges(fork); // non-conflicting
        repository.closeRepository(fork, true);

        preparePatchZip("src/test/resources/content/patch4", "target/karaf/patches/source/patch-4.zip", false);
        List<PatchData> patches = management.fetchPatches(new File("target/karaf/patches/source/patch-4.zip").toURI().toURL());
        Patch patch = management.trackPatch(patches.get(0));

        String tx = management.beginInstallation(PatchKind.ROLLUP);
        management.install(tx, patch, null);

        @SuppressWarnings("unchecked")
        Map<String, Git> transactions = (Map<String, Git>) getField(management, "pendingTransactions");
        assertThat(transactions.size(), equalTo(1));
        fork = transactions.values().iterator().next();

        ObjectId since = fork.getRepository().resolve("baseline-6.2.0^{commit}");
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
                "[PATCH] Rollup patch patch-4 - resetting etc/overrides.properties",
                "[PATCH] Installing rollup patch patch-4");

        int n = 0;
        for (RevCommit c : commits) {
            String msg = c.getShortMessage();
            assertThat(msg, equalTo(commitList.get(n++)));
        }

        assertThat(n, equalTo(commitList.size()));

        assertThat(fork.tagList().call().size(), equalTo(3));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0.redhat-002"));
    }

    @Test
    public void rollbackRollupPatchInstallation() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;

        preparePatchZip("src/test/resources/content/patch4", "target/karaf/patches/source/patch-4.zip", false);
        List<PatchData> patches = management.fetchPatches(new File("target/karaf/patches/source/patch-4.zip").toURI().toURL());
        Patch patch = management.trackPatch(patches.get(0));

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master1 = fork.getRepository().resolve("master");

        String tx = management.beginInstallation(PatchKind.ROLLUP);
        management.install(tx, patch, null);
        management.rollbackInstallation(tx);

        fork.pull().call();
        ObjectId master2 = fork.getRepository().resolve("master");

        assertThat(master1, equalTo(master2));
        assertThat(fork.tagList().call().size(), equalTo(2));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
    }

    @Test
    public void commitRollupPatch() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;

        preparePatchZip("src/test/resources/content/patch4", "target/karaf/patches/source/patch-4.zip", false);
        List<PatchData> patches = management.fetchPatches(new File("target/karaf/patches/source/patch-4.zip").toURI().toURL());
        Patch patch = management.trackPatch(patches.get(0));

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master1 = fork.getRepository().resolve(GitPatchRepository.HISTORY_BRANCH);

        String tx = management.beginInstallation(PatchKind.ROLLUP);
        management.install(tx, patch, null);
        management.commitInstallation(tx);

        repository.closeRepository(fork, true);
        fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master2 = fork.getRepository().resolve(GitPatchRepository.HISTORY_BRANCH);

        assertThat(master1, not(equalTo(master2)));
        assertThat(fork.tagList().call().size(), equalTo(3));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0.redhat-002"));
        assertThat("Baseline should change", repository.findCurrentBaseline(fork).getTagName(), equalTo("baseline-6.2.0.redhat-002"));

        String binStart = FileUtils.readFileToString(new File(karafHome, "bin/start"));
        assertTrue("bin/start should be patched by patch-4",
                binStart.contains("echo \"we had to add this line, because without it, everything crashed\""));

        // we had conflict, so expect the backup
        String backupRef = new RevWalk(fork.getRepository()).parseCommit(master2).getFullMessage().split("\n\n")[1];
        String oldBinStart = FileUtils.readFileToString(new File(karafHome, "patches/patch-4.backup/"
                + backupRef + "/bin/start"));
        assertTrue("bin/start should be backed up",
                oldBinStart.contains("echo \"This is user's change\""));

        assertFalse("There should be no etc/overrides.properties after installing rollup patch",
                new File(karafHome, "etc/overrides.properties").exists());
    }

    @Test
    public void rollbackInstalledRollupPatch() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;

        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        preparePatchZip("src/test/resources/content/patch4", "target/karaf/patches/source/patch-4.zip", false);

        List<PatchData> patches = management.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        Patch patch1 = management.trackPatch(patches.get(0));
        patches = management.fetchPatches(new File("target/karaf/patches/source/patch-4.zip").toURI().toURL());
        Patch patch4 = management.trackPatch(patches.get(0));

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master1 = fork.getRepository().resolve(GitPatchRepository.HISTORY_BRANCH);

        String tx = management.beginInstallation(PatchKind.ROLLUP);
        management.install(tx, patch4, null);
        management.commitInstallation(tx);

        // install P patch to check if rolling back rollup patch will remove P patch's tag
        tx = management.beginInstallation(PatchKind.NON_ROLLUP);
        management.install(tx, patch1, null);
        management.commitInstallation(tx);

        fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        assertTrue(repository.containsTag(fork, "patch-my-patch-1"));

        management.rollback(patch4.getPatchData());

        repository.closeRepository(fork, true);
        fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master2 = fork.getRepository().resolve(GitPatchRepository.HISTORY_BRANCH);

        assertThat(master1, not(equalTo(master2)));
        assertThat(fork.tagList().call().size(), equalTo(2));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
        assertFalse("When rolling back rollup patch, newer P patches' tags should be removed",
                repository.containsTag(fork, "patch-my-patch-1"));
        assertThat(repository.findCurrentBaseline(fork).getTagName(), equalTo("baseline-6.2.0"));

        // TODO: There should be version restored from backed up conflict
        // but we've changed the way rolledback R patch handled - we copy entire WC after rollback
//        String binStart = FileUtils.readFileToString(new File(karafHome, "bin/start"));
//        assertTrue("bin/start should be at previous version",
//                binStart.contains("echo \"This is user's change\""));
    }

    @Test
    public void installNonRollupPatch() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ((GitPatchManagementServiceImpl)pm).applyUserChanges(fork); // no changes, but commit
        repository.prepareCommit(fork, "artificial change, not treated as user change (could be a patch)").call();
        repository.push(fork);
        FileUtils.write(new File(karafHome, "bin/shutdown"), "#!/bin/bash\nexit 42");
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

        ObjectId since = fork.getRepository().resolve("baseline-6.2.0^{commit}");
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

        assertThat(fork.tagList().call().size(), equalTo(3));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
        assertTrue(repository.containsTag(fork, "patch-my-patch-1"));

        assertThat("The conflict should be resolved in special way", FileUtils.readFileToString(new File(karafHome, "bin/setenv")),
                equalTo("JAVA_MIN_MEM=2G # Minimum memory for the JVM\n"));
    }

    @Test
    public void rollbackNonRollupPatchInstallation() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;

        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        List<PatchData> patches = management.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        Patch patch = management.trackPatch(patches.get(0));

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master1 = fork.getRepository().resolve("master");

        String tx = management.beginInstallation(PatchKind.NON_ROLLUP);
        management.install(tx, patch, null);
        management.rollbackInstallation(tx);

        fork.pull().call();
        ObjectId master2 = fork.getRepository().resolve("master");

        assertThat(master1, equalTo(master2));
        assertThat(fork.tagList().call().size(), equalTo(2));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
    }

    @Test
    public void commitNonRollupPatch() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;

        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        List<PatchData> patches = management.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        Patch patch = management.trackPatch(patches.get(0));

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master1 = fork.getRepository().resolve(GitPatchRepository.HISTORY_BRANCH);

        String tx = management.beginInstallation(PatchKind.NON_ROLLUP);
        management.install(tx, patch, null);
        management.commitInstallation(tx);

        repository.closeRepository(fork, true);
        fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master2 = fork.getRepository().resolve(GitPatchRepository.HISTORY_BRANCH);

        assertThat(master1, not(equalTo(master2)));
        assertThat(fork.tagList().call().size(), equalTo(3));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
        assertTrue(repository.containsTag(fork, "patch-my-patch-1"));
        assertThat("Baseline should not change", repository.findCurrentBaseline(fork).getTagName(), equalTo("baseline-6.2.0"));

        String binStart = FileUtils.readFileToString(new File(karafHome, "bin/start"));
        assertTrue("bin/start should be patched by patch-1", binStart.contains("echo \"started\""));

        // we had conflict, so expect the backup
        String oldBinStart = FileUtils.readFileToString(new File(karafHome, "patches/my-patch-1.backup/bin/start"));
        assertTrue("bin/start should be backed up",
                oldBinStart.contains("echo \"This is user's change\""));
    }

    @Test
    public void rollbackInstalledNonRollupPatch() throws IOException, GitAPIException {
        freshKarafStandaloneDistro();
        GitPatchRepository repository = patchManagement();
        PatchManagement management = (PatchManagement) pm;

        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip", false);
        List<PatchData> patches = management.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        Patch patch = management.trackPatch(patches.get(0));

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master1 = fork.getRepository().resolve(GitPatchRepository.HISTORY_BRANCH);

        String tx = management.beginInstallation(PatchKind.NON_ROLLUP);
        management.install(tx, patch, null);
        management.commitInstallation(tx);

        management.rollback(patch.getPatchData());

        repository.closeRepository(fork, true);
        fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        ObjectId master2 = fork.getRepository().resolve(GitPatchRepository.HISTORY_BRANCH);

        assertThat(master1, not(equalTo(master2)));
        assertThat(fork.tagList().call().size(), equalTo(2));
        assertTrue(repository.containsTag(fork, "patch-management"));
        assertTrue(repository.containsTag(fork, "baseline-6.2.0"));
        assertFalse(repository.containsTag(fork, "patch-my-patch-1"));

        String binStart = FileUtils.readFileToString(new File(karafHome, "bin/start"));
        assertTrue("bin/start should be at previous version", binStart.contains("echo \"This is user's change\""));
    }

    private void validateInitialGitRepository() throws IOException, GitAPIException {
        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        pm.ensurePatchManagementInitialized();
        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();

        verify(bsl, atLeastOnce()).setStartLevel(2);

        Git fork = repository.cloneRepository(repository.findOrCreateMainGitRepository(), true);
        List<Ref> tags = fork.tagList().call();
        boolean found = false;
        for (Ref tag : tags) {
            if ("refs/tags/baseline-6.2.0".equals(tag.getName())) {
                found = true;
                break;
            }
        }
        assertTrue("Repository should contain baseline tag for version 6.2.0", found);

        // look in etc/startup.properties for installed patch-management bundle
        List<String> lines = FileUtils.readLines(new File(karafHome, "etc/startup.properties"));
        found = false;
        for (String line : lines) {
            if ("io/fabric8/patch/patch-management/1.1.9/patch-management-1.1.9.jar=2".equals(line)) {
                fail("Should not contain old patch-management bundle in etc/startup.properties");
            }
            if ("io/fabric8/patch/patch-management/1.2.0/patch-management-1.2.0.jar=2".equals(line)) {
                if (found) {
                    fail("Should contain only one declaration of patch-management bundle in etc/startup.properties");
                }
                found = true;
            }
        }

        repository.closeRepository(fork, true);
    }

    /**
     * Install patch management inside fresh karaf distro. No validation is performed.
     * @return
     * @throws IOException
     */
    private GitPatchRepository patchManagement() throws IOException, GitAPIException {
        preparePatchZip("src/test/resources/baselines/baseline1", "target/karaf/system/org/jboss/fuse/jboss-fuse-full/6.2.0/jboss-fuse-full-6.2.0-baseline.zip", true);
        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        pm.ensurePatchManagementInitialized();
        return ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();
    }

}
