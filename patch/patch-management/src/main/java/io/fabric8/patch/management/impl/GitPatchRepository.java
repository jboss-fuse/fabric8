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
package io.fabric8.patch.management.impl;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;

import io.fabric8.patch.management.ManagedPatch;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.transport.PushResult;

/**
 * <p>Interface for low-level git patch repository operations.</p>
 * <p>(makes mocking easier).</p>
 */
public interface GitPatchRepository {

    DateFormat TS = new SimpleDateFormat("yyyyMMdd-HHmmssSSS");
    DateFormat FULL_DATE = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    String HISTORY_BRANCH = "container-history";
    String ADMIN_HISTORY_BRANCH = "admin-container-history";

    /**
     * Call if needed - when patch manager finds that it should use the repository.
     * Prepares resources (like {@link org.eclipse.jgit.api.Git} instance.
     */
    void open() throws IOException, GitAPIException;

    /**
     * Clean up resources
     */
    void close();

    /**
     * Returns {@Git} for main bare git repository
     * @return
     */
    Git findOrCreateMainGitRepository() throws IOException, GitAPIException;

    /**
     * Returns at least initialized git repository at specific location. When the repository doesn't exist, it is
     * created with single branch (with configured name that means main patch branch) and one, empty, initial commit.
     * @param directory
     * @param bare
     * @return
     */
    Git findOrCreateGitRepository(File directory, boolean bare) throws IOException;

    /**
     * Retrieves {@link Git} handle to temporary fork of another repository. The returned repository is connected
     * to the forked repo using "origin" remote.
     * @param git
     * @param fetchAndCheckout whether to checkout main patch branch tracking
     * <code>refs/remotes/origin/&lt;main-patch-branch&gt;</code>
     * @return
     */
    Git cloneRepository(Git git, boolean fetchAndCheckout) throws GitAPIException, IOException;

    /**
     * Closes {@link Git} and if <code>deleteWorkingCopy</code> is <code>true</code>, removes repository and, working copy.
     * @param git
     * @param deleteWorkingCopy
     */
    void closeRepository(Git git, boolean deleteWorkingCopy);

    /**
     * Special <code>git checkout</code> that does several attempts to checkout a revision. This is mainly for
     * Windows...
     * @param git
     * @return
     */
    CheckoutCommand checkout(Git git);

    /**
     * Checks whether a branch in repository contains named commit
     * @param git
     * @param branch
     * @param commitMessage
     * @return
     */
    boolean containsCommit(Git git, String branch, String commitMessage) throws IOException, GitAPIException;

    /**
     * Checks whether the repository contains named tag
     * @param git
     * @param tagName
     * @return
     */
    boolean containsTag(Git git, String tagName) throws GitAPIException;

    /**
     * Returns {@link CommitCommand} with Author and Message set
     * @param git
     * @return
     */
    CommitCommand prepareCommit(Git git, String message);

    /**
     * Shorthand for <code>git push origin <em>main patch branch</em></code>
     * @param git
     */
    void push(Git git) throws GitAPIException;

    /**
     * Shorthand for <code>git push origin <em>branch</em></code>
     * @param git
     */
    void push(Git git, String branch) throws GitAPIException;

    /**
     * Effectively performs <code>git diff commit1..commit2</code>
     * @param git
     * @param commit1
     * @param commit2
     * @return
     */
    List<DiffEntry> diff(Git git, RevCommit commit1, RevCommit commit2) throws GitAPIException, IOException;

    /**
     * Effectively performs <code>git diff commit1..commit2</code> with details specification
     * @param git
     * @param commit1
     * @param commit2
     * @param showNameAndStatusOnly
     * @return
     */
    List<DiffEntry> diff(Git git, RevCommit commit1, RevCommit commit2, boolean showNameAndStatusOnly)
            throws GitAPIException, IOException;

    /**
     * <p>Patch baselines are always tagged in the form <code>baseline-VERSION</code>. This method finds the latest
     * baseline.</p>
     * @param git
     * @return
     */
    RevTag findLatestBaseline(Git git) throws GitAPIException, IOException;

    /**
     * <p>Finds the current baseline, which is the newest baseline tag when traversing down the
     * <code><em>main patch branch</em></code></p>
     * @param repo
     * @return
     */
    RevTag findCurrentBaseline(Git repo) throws GitAPIException, IOException;

    /**
     * <p>Finds one of the previous baselines. <code>n=0</code> means current</p>
     * @param repo
     * @return
     */
    RevTag findNthPreviousBaseline(Git repo, int n) throws GitAPIException, IOException;

    /**
     * Queries git repository for basic {@link ManagedPatch} information (details may be fetched later)
     * @param id
     * @return
     */
    ManagedPatch getManagedPatch(String id) throws IOException;

    /**
     * Iterates the range <code>c1..c2</code> and returns a mapping of tagName -> {@link RevTag} found.
     *
     * @param repo
     * @param c1
     * @param c2
     * @return
     * @throws GitAPIException
     * @throws IOException
     */
    Map<String, RevTag> findTagsBetween(Git repo, RevCommit c1, RevCommit c2) throws GitAPIException, IOException;

    /**
     * Returns the name chosen as <em>main patch branch</em>. If we're running in fabric mode, we don't want to
     * mess with original <code>master</code> branch.
     * @return
     */
    String getMainBranchName();

    /**
     * Returns the name chosen as patch branch for child containers.
     * @return
     */
    String getChildBranchName();

    /**
     * Returns the name chosen as patch branch for SSH containers created from ZIPped Fuse
     * @return
     */
    String getFuseSSHContainerPatchBranchName();

    /**
     * Returns the name chosen as patch branch for SSH containers created from fabric8 distro.
     * @return
     */
    String getFabric8SSHContainerPatchBranchName();

    /**
     * Returns the name chosen as patch branch for root containers created from Fuse distro.
     * @return
     */
    String getFuseRootContainerPatchBranchName();

    /**
     * Returns the name chosen as patch branch for root containers created from AMQ distro.
     * @return
     */
    String getAmqRootContainerPatchBranchName();

    /**
     * Let know the repository, that each push should be followed but push from main repository (fabric mode)
     * @param b
     */
    void setMaster(boolean b);

    /**
     * Retrieves content of File from particular commit (<code>sha1</code>) if exists
     * @param fork
     * @param sha1
     * @param fileName
     * @return
     */
    String getFileContent(Git fork, String sha1, String fileName) throws IOException;

    /**
     * Helper method used when env is {@link io.fabric8.patch.management.EnvType#STANDALONE_CHILD}
     * @return
     */
    String getStandaloneChildkarafName();

    /**
     * Pushes branches related to patches - without pushing locally relevant only branches (like "container-history")
     */
    Iterable<PushResult> pushPatchBranches() throws GitAPIException;
}
