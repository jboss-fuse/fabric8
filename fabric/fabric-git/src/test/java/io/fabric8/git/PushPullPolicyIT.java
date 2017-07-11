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
package io.fabric8.git;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import io.fabric8.api.GitContext;
import io.fabric8.git.internal.DefaultPullPushPolicy;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;

/**
 * I want to gather all possible scenarios related to pulling/pushing between
 * <code>data/git/local/fabric</code> and <code>data/git/servlet/fabric</code>
 */
public class PushPullPolicyIT {

    public static Logger LOG = LoggerFactory.getLogger(GitIT.class);
    private File dirLocal;
    private File dirServlet;
    private Git local;
    private Git servlet;
    private DefaultPullPushPolicy policy;

    @Before
    public void initLocalGitRepositoryAndPushToServletRepository() throws Exception {
        dirLocal = new File("target/data-local");
        dirServlet = new File("target/data-servlet");
        FileUtils.deleteDirectory(dirLocal);
        FileUtils.deleteDirectory(dirServlet);
        local = Git.init().setDirectory(dirLocal).call();
        servlet = Git.init().setDirectory(dirServlet).setBare(true).call();

        // local -> servlet
        local.getRepository().getConfig().setString("remote", "origin", "url", dirServlet.getCanonicalPath());
        local.getRepository().getConfig().setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        local.getRepository().getConfig().save();

        // master branch - ensemble profiles
        prepareMasterBranch();

        // version 1.0 - created by importing initial profiles
        prepare10Branch();

        prepareBranch("1.0.1", "1.0", 3);
        prepareBranch("1.1", "1.0", 4);
        prepareBranch("1.2", "1.1", 2);

        local.push().setRemote("origin").setPushAll().setPushTags().call();

        policy = new DefaultPullPushPolicy(local, "origin", 42);
    }

    @Test
    public void noUpdates() {
        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.1"));
        assertThat(versions.get(3), equalTo("1.2"));
        assertThat(versions.get(4), equalTo("master"));

        assertTrue(result.localUpdateVersions().isEmpty());

        assertFalse(result.remoteUpdateRequired());
    }

    @Test
    public void pushNoUpdates() {
        PullPushPolicy.PushPolicyResult result = policy.doPush(new GitContext(), CP);
        assertNull(result.getLastException());

        // push to only one remote, so one PushResult
        PushResult pr = result.getPushResults().get(0);

        assertThat(pr.getRemoteUpdate("refs/heads/1.0").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/1.0.1").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/1.1").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/1.2").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/master").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/tags/root").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
    }

    @Test
    public void pushLocalBranchAheadAndNotClean() throws IOException, GitAPIException {
        local.checkout().setName("1.1").setCreateBranch(false).call();
        editVersion("1.1", 4, false);

        FileUtils.write(new File(dirLocal, "newFile.txt"), "~");
        FileUtils.write(new File(dirLocal, "fabric/profiles/default.profile/my.special.pid.properties"), "\n# new line", true);

        assertFalse(local.status().call().isClean());

        PullPushPolicy.PushPolicyResult result = policy.doPush(new GitContext(), CP);
        assertNull(result.getLastException());

        // push to only one remote, so one PushResult
        PushResult pr = result.getPushResults().get(0);

        assertThat(pr.getRemoteUpdate("refs/heads/1.0").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/1.0.1").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/1.1").getStatus(), equalTo(RemoteRefUpdate.Status.OK));
        assertThat(pr.getRemoteUpdate("refs/heads/1.2").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/master").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/tags/root").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));

        assertTrue(local.status().call().isClean());
    }

    @Test
    public void pushLocalBranchBehind() throws IOException, GitAPIException {
        local.checkout().setName("1.1").setCreateBranch(false).call();
        local.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD^").call();

        assertTrue(local.status().call().isClean());

        PullPushPolicy.PushPolicyResult result = policy.doPush(new GitContext(), CP);
        assertNull(result.getLastException());

        // push to only one remote, so one PushResult
        PushResult pr = result.getPushResults().get(0);

        assertThat(result.getAcceptedUpdates().size(), equalTo(5));
        assertThat(pr.getRemoteUpdate("refs/heads/1.0").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/1.0.1").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/1.1").getStatus(), equalTo(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD));
        assertThat(pr.getRemoteUpdate("refs/heads/1.2").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/master").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/tags/root").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));

        assertThat(result.getRejectedUpdates().size(), equalTo(1));
        assertThat(result.getRejectedUpdates().get("refs/heads/1.1").getRemoteRefUpdate().getStatus(), equalTo(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD));

        assertTrue(local.status().call().isClean());
    }

    @Test
    public void pushLocalBranchAheadRemoteIsAlsoAhead() throws IOException, GitAPIException {
        local.checkout().setName("1.1").setCreateBranch(false).call();
        local.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD^").call();
        editVersion("1.1", 2, false);

        assertTrue(local.status().call().isClean());

        PullPushPolicy.PushPolicyResult result = policy.doPush(new GitContext(), CP);
        assertNull(result.getLastException());

        // push to only one remote, so one PushResult
        PushResult pr = result.getPushResults().get(0);

        assertThat(result.getAcceptedUpdates().size(), equalTo(5));
        assertThat(pr.getRemoteUpdate("refs/heads/1.0").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/1.0.1").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/1.1").getStatus(), equalTo(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD));
        assertThat(pr.getRemoteUpdate("refs/heads/1.2").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/master").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/tags/root").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));

        assertThat(result.getRejectedUpdates().size(), equalTo(1));
        assertThat(result.getRejectedUpdates().get("refs/heads/1.1").getRemoteRefUpdate().getStatus(), equalTo(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD));

        assertEquals(local.getRepository().getRef("1.1").getName(), servlet.getRepository().getRef("1.1").getName());
        assertTrue(local.status().call().isClean());
    }

    @Test
    public void pushLocalBranchAheadRemoteIsRemoved() throws IOException, GitAPIException {
        local.checkout().setName("1.1").setCreateBranch(false).call();
        editVersion("1.1", 3, false);
        servlet.branchDelete().setForce(true).setBranchNames("1.1").call();

        assertTrue(local.status().call().isClean());

        PullPushPolicy.PushPolicyResult result = policy.doPush(new GitContext(), CP);
        assertNull(result.getLastException());

        // push to only one remote, so one PushResult
        PushResult pr = result.getPushResults().get(0);

        assertThat(result.getAcceptedUpdates().size(), equalTo(6));
        assertThat(pr.getRemoteUpdate("refs/heads/1.0").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/1.0.1").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/1.1").getStatus(), equalTo(RemoteRefUpdate.Status.OK));
        assertThat(pr.getRemoteUpdate("refs/heads/1.2").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/heads/master").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));
        assertThat(pr.getRemoteUpdate("refs/tags/root").getStatus(), equalTo(RemoteRefUpdate.Status.UP_TO_DATE));

        assertThat(result.getRejectedUpdates().size(), equalTo(0));

        assertEquals(local.getRepository().getRef("1.1").getName(), servlet.getRepository().getRef("1.1").getName());
        assertTrue(local.status().call().isClean());
    }

    @Test
    public void remoteUpdateOfSingleBranch() {
        editVersion("1.1", 2, true);

        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(5));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.1"));
        assertThat(versions.get(3), equalTo("1.2"));
        assertThat(versions.get(4), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(1));
        assertThat(localUpdateVersions.get(0), equalTo("1.1"));

        assertFalse(result.remoteUpdateRequired());
    }

    @Test
    public void remoteCreationOfSingleBranch() throws GitAPIException {
        local.checkout().setName("1.1.1").setCreateBranch(true).setStartPoint("1.1").call();
        local.push().setRemote("origin").setRefSpecs(new RefSpec("1.1.1")).call();
        local.checkout().setName("master").setCreateBranch(false).call();
        local.branchDelete().setBranchNames("refs/heads/1.1.1", "refs/remotes/origin/1.1.1").setForce(true).call();

        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(6));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.1"));
        assertThat(versions.get(3), equalTo("1.1.1"));
        assertThat(versions.get(4), equalTo("1.2"));
        assertThat(versions.get(5), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(1));
        assertThat(localUpdateVersions.get(0), equalTo("1.1.1"));

        assertFalse(result.remoteUpdateRequired());

        List<Ref> localBranches = local.branchList().call();
        assertThat(localBranches.size(), equalTo(6));
    }

    @Test
    public void remoteRemovalOfSingleBranch() throws GitAPIException {
        servlet.branchDelete().setForce(true).setBranchNames("1.1").call();

        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(4));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.2"));
        assertThat(versions.get(3), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(1));
        assertThat(localUpdateVersions.get(0), equalTo("1.1"));

        assertFalse(result.remoteUpdateRequired());

        List<Ref> localBranches = local.branchList().call();
        assertThat(localBranches.size(), equalTo(4));
    }

    @Test
    public void remoteRemovalOfSingleBranchWhenLocalBranchIsAheadAndCheckedOut() throws GitAPIException, IOException {
        servlet.branchDelete().setForce(true).setBranchNames("1.1").call();
        editVersion("1.1", 4, false);
        local.checkout().setName("1.1").setCreateBranch(false).call();

        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(4));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.2"));
        assertThat(versions.get(3), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(1));
        assertThat(localUpdateVersions.get(0), equalTo("1.1"));

        assertFalse(result.remoteUpdateRequired());

        List<Ref> localBranches = local.branchList().call();
        assertThat(localBranches.size(), equalTo(4));
        assertNull(local.getRepository().getRef("1.1"));
    }

    @Test
    public void remoteRemovalOfSingleBranchWhenLocalBranchIsAheadAndNotCheckedOut() throws GitAPIException, IOException {
        servlet.branchDelete().setForce(true).setBranchNames("1.1").call();
        local.checkout().setName("1.1").setCreateBranch(false).call();
        editVersion("1.1", 4, false);
        local.checkout().setName("1.0").setCreateBranch(false).call();

        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(4));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.2"));
        assertThat(versions.get(3), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(1));
        assertThat(localUpdateVersions.get(0), equalTo("1.1"));

        assertFalse(result.remoteUpdateRequired());

        List<Ref> localBranches = local.branchList().call();
        assertThat(localBranches.size(), equalTo(4));
        assertNull(local.getRepository().getRef("1.1"));
    }

    @Test
    public void remoteRemovalOfSingleBranchWhenLocalBranchIsCheckedOutAndNotClean() throws GitAPIException, IOException {
        servlet.branchDelete().setForce(true).setBranchNames("1.1").call();
        local.checkout().setName("1.1").setCreateBranch(false).call();

        FileUtils.write(new File(dirLocal, "newFile.txt"), "~");
        FileUtils.write(new File(dirLocal, "fabric/profiles/default.profile/my.special.pid.properties"), "\n# new line", true);

        assertFalse(local.status().call().isClean());

        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(4));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.2"));
        assertThat(versions.get(3), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(1));
        assertThat(localUpdateVersions.get(0), equalTo("1.1"));

        assertFalse(result.remoteUpdateRequired());

        List<Ref> localBranches = local.branchList().call();
        assertThat(localBranches.size(), equalTo(4));
        assertNull(local.getRepository().getRef("1.1"));
    }

    @Test
    public void remoteUpdateWhenLocalBranchIsCheckedOutAndNotClean() throws GitAPIException, IOException {
        editVersion("1.1", 2, true);
        local.checkout().setName("1.1").setCreateBranch(false).call();

        FileUtils.write(new File(dirLocal, "newFile.txt"), "~");
        FileUtils.write(new File(dirLocal, "fabric/profiles/default.profile/my.special.pid.properties"), "\n# new line", true);

        assertFalse(local.status().call().isClean());

        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(5));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.1"));
        assertThat(versions.get(3), equalTo("1.2"));
        assertThat(versions.get(4), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(1));
        assertThat(localUpdateVersions.get(0), equalTo("1.1"));

        assertFalse(result.remoteUpdateRequired());

        List<Ref> localBranches = local.branchList().call();
        assertThat(localBranches.size(), equalTo(5));
        assertNotNull(local.getRepository().getRef("1.1"));
    }

    @Test
    public void remoteUpdateWhenLocalBranchIsAhead() throws GitAPIException, IOException {
        editVersion("1.1", 2, true, "fabric/profiles/default.profile/io.fabric8.other.properties");
        editVersion("1.1", 2, false);
        ObjectId branch1_1local = local.getRepository().getRef("refs/heads/1.1").getObjectId();
        ObjectId branch1_1remote = servlet.getRepository().getRef("refs/heads/1.1").getObjectId();
        assertThat(branch1_1local, not(equalTo(branch1_1remote)));

        local.checkout().setName("1.0").setCreateBranch(false).call();

        assertTrue(local.status().call().isClean());

        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(5));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.1"));
        assertThat(versions.get(3), equalTo("1.2"));
        assertThat(versions.get(4), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(1));
        assertThat(localUpdateVersions.get(0), equalTo("1.1"));

        assertTrue(result.remoteUpdateRequired());
        assertTrue(local.status().call().isClean());

        List<Ref> localBranches = local.branchList().call();
        assertThat(localBranches.size(), equalTo(5));
        assertNotNull(local.getRepository().getRef("1.1"));

        assertThat("Local branch should change by rebase on top of what's in remote", local.getRepository().getRef("refs/heads/1.1").getObjectId(), not(equalTo(branch1_1remote)));
    }

    @Test
    public void remoteUpdateWhenLocalBranchIsAheadButNoPushIsAllowed() throws GitAPIException, IOException {
        editVersion("1.1", 2, true, "fabric/profiles/default.profile/io.fabric8.other.properties");
        editVersion("1.1", 2, false);
        ObjectId branch1_1local = local.getRepository().getRef("refs/heads/1.1").getObjectId();
        ObjectId branch1_1remote = servlet.getRepository().getRef("refs/heads/1.1").getObjectId();
        assertThat(branch1_1local, not(equalTo(branch1_1remote)));

        local.checkout().setName("1.0").setCreateBranch(false).call();

        assertTrue(local.status().call().isClean());

        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true, false);
        assertNull(result.getLastException());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(5));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.1"));
        assertThat(versions.get(3), equalTo("1.2"));
        assertThat(versions.get(4), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(1));
        assertThat(localUpdateVersions.get(0), equalTo("1.1"));

        assertFalse(result.remoteUpdateRequired());
        assertTrue(local.status().call().isClean());

        List<Ref> localBranches = local.branchList().call();
        assertThat(localBranches.size(), equalTo(5));
        assertNotNull(local.getRepository().getRef("1.1"));

        assertThat("Local branch should change (will be reset to remote)", local.getRepository().getRef("refs/heads/1.1").getObjectId(), equalTo(branch1_1remote));
    }

    @Test
    public void noRemoteChangeWhenLocalBranchIsCheckedOutAndNotClean() throws GitAPIException, IOException {
        local.checkout().setName("1.1").setCreateBranch(false).call();

        FileUtils.write(new File(dirLocal, "newFile.txt"), "~");
        FileUtils.write(new File(dirLocal, "fabric/profiles/default.profile/my.special.pid.properties"), "\n# new line", true);

        assertFalse(local.status().call().isClean());

        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        assertTrue(local.status().call().isClean());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(5));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.1"));
        assertThat(versions.get(3), equalTo("1.2"));
        assertThat(versions.get(4), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(0));

        assertFalse(result.remoteUpdateRequired());

        List<Ref> localBranches = local.branchList().call();
        assertThat(localBranches.size(), equalTo(5));
        assertNotNull(local.getRepository().getRef("1.1"));
    }

    @Test
    public void remoteRemovalOfSingleBranchAndUpdateOfAnotherBranch() throws GitAPIException, IOException {
        servlet.branchDelete().setForce(true).setBranchNames("1.1").call();
        editVersion("1.0.1", 3, true);

        Ref ref1 = local.getRepository().getRef("1.0.1");
        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        Ref ref2 = local.getRepository().getRef("1.0.1");

        Iterable<RevCommit> commits = local.log().addRange(ref1.getObjectId(), ref2.getObjectId()).call();
        Iterator<RevCommit> it = commits.iterator();
        int count = 0;
        while (it.hasNext()) {
            it.next();
            count++;
        }
        assertThat(count, equalTo(3));

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(4));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.2"));
        assertThat(versions.get(3), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(2));
        assertTrue(localUpdateVersions.contains("1.0.1"));
        assertTrue(localUpdateVersions.contains("1.1"));

        assertFalse(result.remoteUpdateRequired());

        List<Ref> localBranches = local.branchList().call();
        assertThat(localBranches.size(), equalTo(4));
    }

    @Test
    public void remoteUpdateWhenLocalBranchIsAheadButAlreadyMerged() throws GitAPIException, IOException {
        editVersion("1.1", 2, false);
        local.checkout().setName("1.0").setCreateBranch(false).call();

        PullPushPolicy.PullPolicyResult result = policy.doPull(new GitContext(), CP, true);
        assertNull(result.getLastException());

        List<String> versions = new ArrayList<>(result.getVersions());
        // these are sorted (TreeSet)
        assertThat(versions.size(), equalTo(5));
        assertThat(versions.get(0), equalTo("1.0"));
        assertThat(versions.get(1), equalTo("1.0.1"));
        assertThat(versions.get(2), equalTo("1.1"));
        assertThat(versions.get(3), equalTo("1.2"));
        assertThat(versions.get(4), equalTo("master"));

        List<String> localUpdateVersions = new ArrayList<>(result.localUpdateVersions().keySet());
        assertThat(localUpdateVersions.size(), equalTo(0));

        // if remote commit is already present in local branch, which has additional commits, we have to push back
        // to central repo
        assertTrue(result.remoteUpdateRequired());
        assertTrue(local.status().call().isClean());
    }

    private void prepareMasterBranch() throws GitAPIException, IOException {
        commit(local, "First message");
        local.tag().setName("root").call();

        String props = "attribute.abstract = true\n" +
                "attribute.hidden = true";
        FileUtils.write(new File(dirLocal, "fabric/profiles/fabric/ensemble/0000.profile/io.fabric8.agent.properties"), props);
        props = "#Mon Jan 1 01:02:03 CEST 2042\n" +
                "snapRetainCount=3\n" +
                "purgeInterval=0\n" +
                "dataDir=zookeeper/0000\n" +
                "syncLimit=5\n" +
                "initLimit=10\n" +
                "tickTime=2000";
        FileUtils.write(new File(dirLocal, "fabric/profiles/fabric/ensemble/0000.profile/io.fabric8.zookeeper.server-0000.properties"), props);
        local.add().addFilepattern(".").call();
        commit(local, "Create profile: fabric-ensemble-0000");

        props = "attribute.hidden = true\n" +
                "attribute.parents = fabric-ensemble-0000";
        FileUtils.write(new File(dirLocal, "fabric/profiles/fabric/ensemble/0000/1.profile/io.fabric8.agent.properties"), props);
        props = "#Mon Jan 1 01:02:04 CEST 2042\n" +
                "clientPortAddress=0.0.0.0\n" +
                "clientPort=2181";
        FileUtils.write(new File(dirLocal, "fabric/profiles/fabric/ensemble/0000/1.profile/io.fabric8.zookeeper.server-0000.properties"), props);
        local.add().addFilepattern(".").call();
        commit(local, "Create profile: fabric-ensemble-0000-1");
    }

    private void prepare10Branch() throws GitAPIException, IOException {
        local.checkout().setName("1.0").setCreateBranch(true).setStartPoint("root").call();

        File source = new File("src/test/resources/distros/distro2/fabric/import");
        FileUtils.copyDirectory(source, dirLocal);
        local.add().addFilepattern(".").call();
        commit(local, "Imported from " + source.getCanonicalPath());
        commit(local, "Update configurations for profile: default");
        commit(local, "Update configurations for profile: default");
    }

    /**
     * Simulates <code>profile-edit</code> invocations in newly created version
     * @param version version to create
     * @param parent parent of created version
     * @param numberOfEdits number of simulated <code>profile-edit</code> invocations
     */
    private void prepareBranch(String version, String parent, int numberOfEdits) {
        try {
            local.checkout().setName(version).setCreateBranch(true).setStartPoint(parent).call();

            for (int edit = 0; edit < numberOfEdits; edit++) {
                FileUtils.write(new File(dirLocal, "fabric/profiles/default.profile/io.fabric8.agent.properties"), String.format("\nedit%d = %d", edit + 1, edit + 1), true);
                local.add().addFilepattern(".").call();
                commit(local, "Update configurations for profile: default, version: " + version);
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Simulates <code>profile-edit</code> invocations in existing version
     * @param version
     * @param numberOfEdits
     * @param inRemote
     */
    private void editVersion(String version, int numberOfEdits, boolean inRemote) {
        editVersion(version, numberOfEdits, inRemote, "fabric/profiles/default.profile/io.fabric8.agent.properties");
    }

    /**
     * Simulates <code>profile-edit</code> invocations in existing version
     * @param version
     * @param numberOfEdits
     * @param inRemote whether to perform edits in remote repository
     * @param fileName file to edit
     */
    private void editVersion(String version, int numberOfEdits, boolean inRemote, String fileName) {
        Git git = local;
        try {
            if (inRemote) {
                File dirTmp = new File(dirServlet.getParentFile(), "data-tmp");
                FileUtils.deleteDirectory(dirTmp);
                git = Git.cloneRepository()
                        .setURI(dirServlet.toURI().toString())
                        .setDirectory(dirTmp).call();
            }

            boolean create = true;
            List<Ref> branches = git.branchList().call();
            for (Ref b : branches) {
                if (b.getName().equals("refs/heads/" + version)) {
                    create = false;
                    break;
                }
            }
            git.checkout().setName(version)
                    .setStartPoint("origin/" + version)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .setCreateBranch(create).call();

            for (int edit = 0; edit < numberOfEdits; edit++) {
                FileUtils.write(new File(git.getRepository().getWorkTree(), fileName), String.format("\nedit%d = %d # %s", edit + 1, edit + 1, UUID.randomUUID().toString()), true);
                git.add().addFilepattern(".").call();
                commit(git, "Update configurations for profile: default, version: " + version);
            }

            if (inRemote) {
                git.push().setRemote("origin").setPushAll().setPushTags().call();
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private void commit(Git git, String message) throws GitAPIException {
        git.commit().setAuthor("junit", "junit@dev.null").setMessage(message).call();
    }

    private static final CredentialsProvider CP = new MyCredentialsProvider();

    private static class MyCredentialsProvider extends CredentialsProvider {

        @Override
        public boolean isInteractive() {
            return false;
        }

        @Override
        public boolean supports(CredentialItem... items) {
            return false;
        }

        @Override
        public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
            return false;
        }

    }

}
