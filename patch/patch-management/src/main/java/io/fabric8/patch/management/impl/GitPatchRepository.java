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

import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

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
     * @param fetchAndCheckout
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

}
