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
package io.fabric8.git;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

import io.fabric8.git.internal.GitHelpers;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

public class GitIT {

    public static Logger LOG = LoggerFactory.getLogger(GitIT.class);
    private File dir;
    private Git git;

    @Before
    public void initGitRepoCreateTwoBranchesCheckoutFirstBranch() throws Exception {
        dir = new File("target/git-test-repo");
        FileUtils.deleteDirectory(dir);
        git = Git.init().setDirectory(dir).setGitDir(new File(dir, ".git")).call();

        // master branch
        FileWriter writer = new FileWriter(new File(dir, "version.attributes"));
        IOUtils.write("master", writer);
        IOUtils.closeQuietly(writer);
        File subdir = new File(dir, "subdir");
        subdir.mkdir();
        writer = new FileWriter(new File(subdir, "version.attributes")); // to test recursive reads
        IOUtils.write("irrelevant-master", writer);
        IOUtils.closeQuietly(writer);
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Initial commit").call();

        // 1.0 branch
        git.checkout().setCreateBranch(true).setName("1.0").call();
        writer = new FileWriter(new File(dir, "version.attributes"));
        IOUtils.write("1.0", writer);
        IOUtils.closeQuietly(writer);
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Create version 1.0, new branch").call();

        // 1.1 branch
        git.checkout().setName("1.0").setForce(false).call();
        git.checkout().setCreateBranch(true).setName("1.1").call();
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Create version 1.1, new branch").call();

        LOG.info("Branches");
        List<Ref> refs = git.branchList().call();
        for (Ref ref : refs) {
            LOG.info("\t{}", ref.getName());
        }
    }

    @Test
    public void showContentOfFileFromNotCurrentlyCheckedOutBranch() throws Exception {
        String content = FileUtils.readFileToString(new File(dir, "version.attributes"));
        assertThat(content, equalTo("1.0"));

        RevCommit rw = new RevWalk(git.getRepository()).parseCommit(git.getRepository().getRef("refs/heads/master").getObjectId());
        TreeWalk tw = new TreeWalk(git.getRepository());
        tw.addTree(rw.getTree());
        tw.setRecursive(false);
        tw.setFilter(PathFilter.create("version.attributes"));
        assertThat(tw.next(), equalTo(true));
        ObjectId objectId = tw.getObjectId(0);
        ObjectLoader loader = git.getRepository().open(objectId);
        assertThat(new String(loader.getBytes()), equalTo("master"));
    }

    @Test
    public void showContentOfFileFromNotCurrentlyCheckedOutBranchUsingGitHelper() throws Exception {
        String content = FileUtils.readFileToString(new File(dir, "version.attributes"));
        assertThat(content, equalTo("1.0"));

        byte[] bytesMaster = GitHelpers.getContentOfObject(git, "master", "version.attributes", true);
        byte[] bytesMaster2 = GitHelpers.getContentOfObject(git, "master", "subdir/version.attributes", true);
        byte[] bytes1_0 = GitHelpers.getContentOfObject(git, "1.0", "version.attributes", true);
        byte[] bytes1_1 = GitHelpers.getContentOfObject(git, "1.1", "version.attributes", true);
        byte[] bytes1_1checkParent = GitHelpers.getContentOfObject(git, "1.1", "version.attributes", false);
        assertNotNull(bytesMaster);
        assertNotNull(bytesMaster2);
        assertNotNull(bytes1_0);
        assertThat(new String(bytesMaster), equalTo("master"));
        assertThat(new String(bytesMaster2), equalTo("irrelevant-master"));
        assertThat(new String(bytes1_0), equalTo("1.0"));
        assertThat(bytes1_1, nullValue());
        assertThat(new String(bytes1_1checkParent), equalTo("1.0"));
    }

}
