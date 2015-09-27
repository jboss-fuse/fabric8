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
import java.util.Date;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;

/**
 * Data-layer service that uses jGit to perform git operations for patch manageer
 */
public class GitPatchRepositoryImpl implements GitPatchRepository {

    private static final String MAIN_GIT_REPO_LOCATION = ".management/history";

    private static final DateFormat TS = new SimpleDateFormat("yyyyMMdd-HHmmssSSS");

    // ${karaf.home}
    private File karafHome;
    // main patches directory at ${fuse.patch.location} (defaults to ${karaf.home}/patches)
    private File patchesDir;

    // directory containing "reference", bare git repository to store patch management history
    private File gitPatchManagement;
    // main git (bare) repository at ${fuse.patch.location}/.management/history
    private Git mainRepository;

    // directory to store temporary forks and working copies to perform operations before pushing to
    // "reference" repository
    private File tmpPatchManagement;

    public GitPatchRepositoryImpl(File karafHome, File patchesDir) {
        this.karafHome = karafHome;
        this.patchesDir = patchesDir;
    }

    @Override
    public void open() {
        gitPatchManagement = new File(patchesDir, MAIN_GIT_REPO_LOCATION);
        if (!gitPatchManagement.exists()) {
            gitPatchManagement.mkdirs();
        }

        tmpPatchManagement = new File(patchesDir, "tmp");
        if (!tmpPatchManagement.exists()) {
            tmpPatchManagement.mkdirs();
        }
    }

    @Override
    public void close() {
        if (mainRepository != null) {
            mainRepository.close();
        }
        RepositoryCache.clear();
    }

    @Override
    public Git findOrCreateMainGitRepository() throws IOException {
        return findOrCreateGitRepository(gitPatchManagement, true);
    }

    @Override
    public Git findOrCreateGitRepository(File directory, boolean bare) throws IOException {
        try {
            return Git.open(directory);
        } catch (RepositoryNotFoundException fallback) {
            try {
                Git git = Git.init()
                        .setBare(bare)
                        .setDirectory(directory)
                        .call();
                Git fork = cloneRepository(git, false);
                commit(fork, "[PATCH] initialization").call();
                push(fork);
                closeRepository(fork, true);
                return git;
            } catch (GitAPIException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    @Override
    public Git cloneRepository(Git git, boolean fetchAndCheckout) throws GitAPIException, IOException {
        File tmpLocation = new File(tmpPatchManagement, TS.format(new Date()));
        Git fork = Git.init().setBare(false).setDirectory(tmpLocation).call();
        StoredConfig config = fork.getRepository().getConfig();
        config.setString("remote", "origin", "url", git.getRepository().getDirectory().getCanonicalPath());
        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        config.save();

        if (fetchAndCheckout) {
            fork.fetch().setRemote("origin").call();
            fork.checkout()
                    .setCreateBranch(true)
                    .setName("master")
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .setStartPoint("origin/master")
                    .call();
        }

        return fork;
    }

    @Override
    public void closeRepository(Git git, boolean deleteWorkingCopy) {
        git.close();
        if (deleteWorkingCopy) {
            FileUtils.deleteQuietly(git.getRepository().getDirectory().getParentFile());
        }
    }

    @Override
    public boolean containsCommit(Git git, String branch, String commitMessage) throws IOException, GitAPIException {
        ObjectId head = git.getRepository().resolve(branch);
        Iterable<RevCommit> log = git.log().add(head).call();
        for (RevCommit rc : log) {
            if (rc.getFullMessage().equals(commitMessage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@link CommitCommand} with Author and Message set
     * @param git
     * @return
     */
    @Override
    public CommitCommand commit(Git git, String message) {
        return git.commit()
                .setAuthor(karafHome.getName(), "fuse@redhat.com")
                .setMessage(message);
    }

    /**
     * Shorthand for <code>git push origin master</code>
     * @param git
     */
    @Override
    public void push(Git git) throws GitAPIException {
        git.push()
                .setRemote("origin")
                .setRefSpecs(new RefSpec("master"))
                .call();
    }

}
