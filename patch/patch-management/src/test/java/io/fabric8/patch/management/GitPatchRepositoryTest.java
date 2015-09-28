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

import io.fabric8.patch.management.impl.GitPatchRepository;
import io.fabric8.patch.management.impl.GitPatchRepositoryImpl;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.*;

public class GitPatchRepositoryTest {

    private File karafHome;
    private File patchesHome;

    private GitPatchRepository repository;

    @Before
    public void init() {
        karafHome = new File("target/karaf");
        FileUtils.deleteQuietly(karafHome);
        patchesHome = new File(karafHome, "patches");

        repository = new GitPatchRepositoryImpl(karafHome, patchesHome);
        repository.open();
    }

    @Test
    public void directoriesCreated() {
        assertTrue(new File(patchesHome, GitPatchRepositoryImpl.MAIN_GIT_REPO_LOCATION).exists());
    }

    @Test
    public void mainRepository() throws IOException {
        Git main = repository.findOrCreateMainGitRepository();
        assertNotNull(main);
        assertTrue(main.getRepository().isBare());
        try {
            main.getRepository().getWorkTree();
            fail("Should not contain working tree");
        } catch (NoWorkTreeException expected) {
        }

        assertThat("Should contain single ref", new File(main.getRepository().getDirectory(), "refs/heads").listFiles().length, equalTo(1));
        File theOnlyHead = new File(main.getRepository().getDirectory(), "refs/heads/master");
        assertTrue(theOnlyHead.exists());
    }

    @Test
    public void creatingRepositories() throws IOException, GitAPIException {
        Git repo1 = repository.findOrCreateGitRepository(new File(patchesHome, "repo1"), false);

        assertNotNull(repo1.getRepository().getWorkTree());
        File dir = repo1.getRepository().getWorkTree().listFiles()[0];
        assertThat(dir.getName(), equalTo(".git"));
        assertTrue(dir.isDirectory());

        Iterator<RevCommit> commits = repo1.log().call().iterator();
        assertTrue(commits.hasNext());
        commits.next();
        assertFalse(commits.hasNext());
    }

    @Test
    public void cloneMainRepository() throws IOException, GitAPIException {
        Git main = repository.findOrCreateMainGitRepository();
        Git clone1 = repository.cloneRepository(main, true);
        assertThat(clone1.branchList().call().size(), equalTo(1));
        assertThat(clone1.branchList().call().get(0).getName(), equalTo("refs/heads/master"));
        Git clone2 = repository.cloneRepository(main, false);
        assertThat(clone2.branchList().call().size(), equalTo(0));

        assertTrue(repository.containsCommit(clone1, "master", "[PATCH] initialization"));
    }

    @Test
    public void clonedRepositoryIsConnectedToOriginal() throws IOException, GitAPIException {
        Git repo = repository.findOrCreateGitRepository(new File(patchesHome, "r1"), false);
        Git clone1 = repository.cloneRepository(repo, true);
        assertThat(clone1.getRepository().getConfig().getString("remote", "origin", "url"),
                equalTo(repo.getRepository().getDirectory().getCanonicalPath()));

        FileUtils.write(new File(clone1.getRepository().getWorkTree(), "file.txt"), "hello!");
        clone1.add().addFilepattern("file.txt").call();
        repository.prepareCommit(clone1, "my commit").call();
        repository.push(clone1);

        assertFalse(new File(repo.getRepository().getWorkTree(), "file.txt").exists());
        repo.checkout().setName("master").setStartPoint("refs/heads/master").setCreateBranch(false).call();
        assertTrue(new File(repo.getRepository().getWorkTree(), "file.txt").exists());

        Git clone2 = repository.cloneRepository(repo, true);
        String content = FileUtils.readFileToString(new File(clone2.getRepository().getWorkTree(), "file.txt"));
        assertThat(content, equalTo("hello!"));
    }

}
