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
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import io.fabric8.patch.management.impl.GitPatchRepository;
import io.fabric8.patch.management.impl.GitPatchRepositoryImpl;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.*;

public class GitPatchRepositoryIT {

    private File karafHome;
    private File karafData;
    private File patchesHome;

    private GitPatchRepository repository;

    @Before
    public void init() throws IOException, GitAPIException {
        karafHome = new File("target/karaf-" + (new Date()).getTime());
        karafData = new File(karafHome, "data");
        FileUtils.deleteQuietly(karafHome);
        patchesHome = new File(karafHome, "patches");
        File patchRepositoryLocation = new File(patchesHome, GitPatchRepositoryImpl.MAIN_GIT_REPO_LOCATION);

        repository = new GitPatchRepositoryImpl(EnvType.STANDALONE, patchRepositoryLocation, karafHome, karafHome, karafData, patchesHome);
        repository.open();
    }

    @Test
    public void directoriesCreated() {
        assertTrue(new File(patchesHome, GitPatchRepositoryImpl.MAIN_GIT_REPO_LOCATION).exists());
    }

    @Test
    public void mainRepository() throws IOException, GitAPIException {
        Git main = repository.findOrCreateMainGitRepository();
        assertNotNull(main);
        assertTrue(main.getRepository().isBare());
        try {
            main.getRepository().getWorkTree();
            fail("Should not contain working tree");
        } catch (NoWorkTreeException expected) {
        }

        assertThat("Should contain single ref", new File(main.getRepository().getDirectory(), "refs/heads").listFiles().length, equalTo(1));
        File theOnlyHead = new File(main.getRepository().getDirectory(), "refs/heads/" + GitPatchRepository.HISTORY_BRANCH);
        assertTrue(theOnlyHead.exists());
    }

    @Test
    public void creatingRepositories() throws IOException, GitAPIException {
        Git repo1 = repository.findOrCreateGitRepository(new File(patchesHome, "repo1"), false);
        repo1.checkout().setName(GitPatchRepository.HISTORY_BRANCH).call();

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
        assertThat(clone1.branchList().call().get(0).getName(), equalTo("refs/heads/" + GitPatchRepository.HISTORY_BRANCH));
        Git clone2 = repository.cloneRepository(main, false);
        assertThat(clone2.branchList().call().size(), equalTo(0));

        assertTrue(repository.containsCommit(clone1, GitPatchRepository.HISTORY_BRANCH, "[PATCH] initialization"));

        repository.closeRepository(clone1, true);
        repository.closeRepository(clone2, true);
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
        repo.checkout().setName(GitPatchRepository.HISTORY_BRANCH)
                .setStartPoint("refs/heads/" + GitPatchRepository.HISTORY_BRANCH)
                .setCreateBranch(false)
                .call();
        assertTrue(new File(repo.getRepository().getWorkTree(), "file.txt").exists());

        Git clone2 = repository.cloneRepository(repo, true);
        String content = FileUtils.readFileToString(new File(clone2.getRepository().getWorkTree(), "file.txt"));
        assertThat(content, equalTo("hello!"));

        repository.closeRepository(clone1, true);
        repository.closeRepository(clone2, true);
    }

    @Test
    public void containsTag() throws IOException, GitAPIException {
        Git repo = repository.findOrCreateGitRepository(new File(patchesHome, "r2"), false);
        RevCommit c1 = repository.prepareCommit(repo, "commit1").call();
        RevCommit c2 = repository.prepareCommit(repo, "commit2").call();
        RevCommit c3 = repository.prepareCommit(repo, "commit3").call();

        repo.tag().setName("t1").setObjectId(c1).call();
        repo.tag().setName("t3").setObjectId(c3).call();

        assertTrue(repository.containsTag(repo, "t1"));
        assertFalse(repository.containsTag(repo, "t2"));
        assertTrue(repository.containsTag(repo, "t3"));

        RevWalk rw = new RevWalk(repo.getRepository());
        RevTag t1 = rw.parseTag(repo.getRepository().getRef("t1").getObjectId());
        RevTag t3 = rw.parseTag(repo.getRepository().getRef("t3").getObjectId());
        assertThat(t1.getObject().getId(), equalTo(c1.getId()));
        assertThat(t3.getObject().getId(), equalTo(c3.getId()));
    }

    @Test
    public void findTags() throws Exception {
        Git repo = repository.findOrCreateGitRepository(new File(patchesHome, "r2"), false);
        repo.checkout().setName(GitPatchRepository.HISTORY_BRANCH).call();
        RevCommit c1 = repository.prepareCommit(repo, "commit1").call();
        RevCommit c2 = repository.prepareCommit(repo, "commit2").call();
        RevCommit c3 = repository.prepareCommit(repo, "commit3").call();
        RevCommit c4 = repository.prepareCommit(repo, "commit4").call();
        RevCommit c5 = repository.prepareCommit(repo, "commit5").call();
        RevCommit c6 = repository.prepareCommit(repo, "commit6").call();
        RevCommit c7 = repository.prepareCommit(repo, "commit7").call();
        RevCommit c8 = repository.prepareCommit(repo, "commit8").call();
        RevCommit c9 = repository.prepareCommit(repo, "commit9").call();

        repo.tag().setName("t1").setObjectId(c1).call();
        repo.tag().setName("t3a").setObjectId(c3).call();
        repo.tag().setName("t3b").setObjectId(c3).call();

        Map<String, RevTag> tags = repository.findTagsBetween(repo, c1.getParent(0), c9);
        assertThat(tags.get("t1").getTagName(), equalTo("t1"));
        assertThat(tags.get("t3a").getTagName(), equalTo("t3a"));
        assertThat(tags.get("t3b").getTagName(), equalTo("t3b"));
        assertThat(tags.size(), equalTo(3));

        assertThat(repo.getRepository().resolve(tags.get("t1").getName() + "^{commit}"), equalTo(c1.getId()));
        assertThat(repo.getRepository().resolve(tags.get("t3a").getName() + "^{commit}"), equalTo(c3.getId()));
        assertThat(repo.getRepository().resolve(tags.get("t3b").getName() + "^{commit}"), equalTo(c3.getId()));
    }

    @Test
    public void latestTag() throws GitAPIException, IOException {
        Git repo = repository.findOrCreateGitRepository(new File(patchesHome, "r3"), false);
        RevCommit c1 = repository.prepareCommit(repo, "commit1").call();
        RevCommit c2 = repository.prepareCommit(repo, "commit2").call();
        RevCommit c3 = repository.prepareCommit(repo, "commit3").call();

        repo.tag().setName("baseline-1.2.3").setObjectId(c1).call();
        repo.tag().setName("baseline-y-z-1.2.4").setObjectId(c1).call();
        // lower version, newer commit/tag
        repo.tag().setName("baseline-1.2.1").setObjectId(c3).call();
        repo.tag().setName("baseline-x-1.2.2").setObjectId(c3).call();

        RevTag tag = repository.findLatestBaseline(repo);
        assertThat(tag.getTagName(), equalTo("baseline-y-z-1.2.4"));
        assertThat(tag.getObject(), equalTo(c1.getId()));
    }

    @Test
    public void currentBaseline() throws GitAPIException, IOException {
        Git repo = repository.findOrCreateGitRepository(new File(patchesHome, "r4"), false);
        repo.checkout().setName(GitPatchRepository.HISTORY_BRANCH).call();
        RevCommit c1 = repository.prepareCommit(repo, "commit1").call();
        repo.tag().setName("baseline-1.2.3").setObjectId(c1).call();
        RevCommit c2 = repository.prepareCommit(repo, "commit2").call();
        repo.tag().setName("baseline-1.2.1").setObjectId(c2).call(); // for the purpose of test
        repo.tag().setName("baseline-in-fabric-1.2.1").setObjectId(c2).call(); // 2nd tag for the same commit
        RevCommit c3 = repository.prepareCommit(repo, "commit3").call();

        RevTag tag1 = repository.findLatestBaseline(repo);
        assertThat(tag1.getTagName(), equalTo("baseline-1.2.3"));
        assertThat(tag1.getObject(), equalTo(c1.getId()));
        RevTag tag2 = repository.findCurrentBaseline(repo);
        assertThat(tag2.getTagName(), equalTo("baseline-in-fabric-1.2.1"));
        assertThat(tag2.getObject(), equalTo(c2.getId()));
    }

    @Test
    public void logTwoDots() throws GitAPIException, IOException {
        Git repo = repository.findOrCreateGitRepository(new File(patchesHome, "r5"), false);
        RevCommit c1 = repository.prepareCommit(repo, "commit1").call();
        RevCommit c2 = repository.prepareCommit(repo, "commit2").call();
        RevCommit c3 = repository.prepareCommit(repo, "commit3").call();
        repo.tag().setName("x").setObjectId(c1).call();
        RevCommit c4 = repository.prepareCommit(repo, "commit4").call();
        RevCommit c5 = repository.prepareCommit(repo, "commit5").call();
        RevCommit c6 = repository.prepareCommit(repo, "commit6").call();

        ObjectId tag = repo.getRepository().resolve("x^{commit}");
        Iterable<RevCommit> commits = repo.log().addRange(tag, c6).call();
        Set<String> messages = new HashSet<>(Arrays.asList("commit4", "commit5", "commit6"));
        int n = 6;
        for (RevCommit c : commits) {
            String msg = c.getFullMessage();
            assertThat(msg, equalTo(String.format("commit%d", n--)));
            messages.remove(msg);
        }

        assertTrue(messages.isEmpty());
    }

}
