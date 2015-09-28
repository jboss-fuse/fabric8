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
import java.util.List;

import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;

/**
 * <p>Interface for low-level git patch repository operations.</p>
 * <p>(makes mocking easier).</p>
 */
public interface GitPatchRepository {

    /**
     * Call if needed - when patch manager finds that it should use the repository.
     * Prepares resources (like {@link org.eclipse.jgit.api.Git} instance.
     */
    void open();

    /**
     * Clean up resources
     */
    void close();

    /**
     * Returns {@Git} for main bare git repository
     * @return
     */
    Git findOrCreateMainGitRepository() throws IOException;

    /**
     * Returns at least initialized git repository at specific location. When the repository doesn't exist, it is
     * created with single branch <code>master</code> and one, empty, initial commit.
     * @param directory
     * @param bare
     * @return
     */
    Git findOrCreateGitRepository(File directory, boolean bare) throws IOException;

    /**
     * Retrieves {@link Git} handle to temporary fork of another repository. The returned repository is connected
     * to the forked repo using "origin" remote.
     * @param git
     * @param fetchAndCheckout whether to checkout <code>master</code> branch tracking <code>refs/remotes/origin/master</code>
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
     * Shorthand for <code>git push origin master</code>
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
     * <p>Patch baselines are always tagged in the form <code>baseline-VERSION</code>. This method finds the latest
     * baseline.</p>
     * @param git
     * @return
     */
    RevTag findLatestBaseline(Git git) throws GitAPIException, IOException;

}
