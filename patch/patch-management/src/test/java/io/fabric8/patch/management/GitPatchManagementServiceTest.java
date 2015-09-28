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
import java.util.Iterator;
import java.util.List;

import io.fabric8.patch.management.impl.GitPatchManagementService;
import io.fabric8.patch.management.impl.GitPatchManagementServiceImpl;
import io.fabric8.patch.management.impl.GitPatchRepository;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.startlevel.BundleStartLevel;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GitPatchManagementServiceTest extends PatchTestSupport {

    private GitPatchManagementService pm;
    private BundleStartLevel bsl;

    @Before
    public void init() throws IOException {
        super.init();

        bsl = mock(BundleStartLevel.class);
        when(bundle.adapt(BundleStartLevel.class)).thenReturn(bsl);

        when(systemContext.getDataFile("patches")).thenReturn(new File(karafHome, "data/cache/bundle0/data/patches"));
    }

    @Test
    public void disabledPatchManagement() {
        properties.remove("fuse.patch.location");
        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        assertFalse(pm.isEnabled());
    }

    @Test
    public void enabledPatchManagement() {
        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        assertTrue(pm.isEnabled());
    }

    @Test
    public void initializationPerformedNoFuseVersion() {
        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        try {
            pm.ensurePatchManagementInitialized();
            fail("Should fail, because versions can't be determined");
        } catch (PatchException e) {
            assertTrue(e.getMessage().contains("Can't determine Fuse/Fabric8 version"));
        }
    }

    @Test
    public void initializationPerformedNoBaselineDistribution() throws IOException {
        freshKarafDistro();
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
        freshKarafDistro();
        preparePatchZip("src/test/resources/baselines/baseline1", "target/karaf/patches/jboss-fuse-full-6.2.0-baseline.zip", true);
        validateInitialGitRepository();
    }

    @Test
    public void initializationPerformedBaselineDistributionFoundInSystem() throws IOException, GitAPIException {
        freshKarafDistro();
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
        freshKarafDistro();
        String line = String.format("io/fabric8/patch/patch-management/%s/patch-management-%s.jar=2\n", version, version);
        FileUtils.write(new File(karafHome, "etc/startup.properties"), line, true);
        preparePatchZip("src/test/resources/baselines/baseline1", "target/karaf/system/org/jboss/fuse/jboss-fuse-full/6.2.0/jboss-fuse-full-6.2.0-baseline.zip", true);
        validateInitialGitRepository();
    }

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
            if (String.format("refs/remotes/origin/%s", patchData.getId()).equals(remoteBranch.getName())) {
                patchBranch = remoteBranch;
                break;
            }
        }
        assertNotNull("Should find remote branch for the added patch", patchBranch);

        RevCommit patchCommit = new RevWalk(fork.getRepository()).parseCommit(patchBranch.getObjectId());
        // patch commit should be child of baseline commit
        RevCommit baselineCommit = new RevWalk(fork.getRepository()).parseCommit(patchCommit.getParent(0));

        // this baseline commit should be tagged "baseline-VERSION"
        Ref tag = fork.tagList().call().get(0);
        assertThat(tag.getName(), equalTo("refs/tags/baseline-6.2.0"));
        RevCommit baselineCommitFromTag = new RevWalk(fork.getRepository()).parseCommit(tag.getTarget().getObjectId());
        assertThat(baselineCommit.getId(), equalTo(baselineCommitFromTag.getId()));

        List<DiffEntry> patchDiff = repository.diff(fork, baselineCommit, patchCommit);
        assertThat("patch-4 should lead to 6 changes", patchDiff.size(), equalTo(6));
        for (Iterator<DiffEntry> iterator = patchDiff.iterator(); iterator.hasNext(); ) {
            DiffEntry de = iterator.next();
            if ("bin/start".equals(de.getNewPath()) && de.getChangeType() == DiffEntry.ChangeType.MODIFY) {
                iterator.remove();
            }
            if ("bin/stop".equals(de.getNewPath()) && de.getChangeType() == DiffEntry.ChangeType.MODIFY) {
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
        }

        assertThat("Unknown changes in patch-4", patchDiff.size(), equalTo(0));

        // let's see the patch applied to baseline-6.2.0
        fork.checkout()
                .setName("patch-4")
                .setStartPoint("origin/patch-4")
                .setCreateBranch(true)
                .call();
        String startupProperties = FileUtils.readFileToString(new File(fork.getRepository().getWorkTree(), "etc/startup.properties"));
        assertTrue(startupProperties.contains("org/ops4j/pax/url/pax-url-gopher/2.4.0/pax-url-gopher-2.4.0.jar=5"));

        repository.closeRepository(fork, true);
    }

    private void validateInitialGitRepository() throws IOException, GitAPIException {
        pm = new GitPatchManagementServiceImpl(bundleContext);
        pm.start();
        pm.ensurePatchManagementInitialized();
        GitPatchRepository repository = ((GitPatchManagementServiceImpl) pm).getGitPatchRepository();

        verify(bsl).setStartLevel(2);

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
     * Create crucial Karaf files (like etc/startup.properties)
     */
    private void freshKarafDistro() throws IOException {
        FileUtils.copyFile(new File("src/test/resources/karaf/etc/startup.properties"), new File(karafHome, "etc/startup.properties"));
        FileUtils.copyFile(new File("src/test/resources/karaf/bin/start"), new File(karafHome, "bin/start"));
        FileUtils.copyFile(new File("src/test/resources/karaf/bin/stop"), new File(karafHome, "bin/stop"));
        FileUtils.copyFile(new File("src/test/resources/karaf/lib/karaf.jar"), new File(karafHome, "lib/karaf.jar"));
        FileUtils.copyFile(new File("src/test/resources/karaf/fabric/import/fabric/profiles/default.profile/io.fabric8.version.properties"),
                new File(karafHome, "fabric/import/fabric/profiles/default.profile/io.fabric8.version.properties"));
        new File(karafHome, "licenses").mkdirs();
        new File(karafHome, "metatype").mkdirs();
    }

}
