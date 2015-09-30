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
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric8.patch.management.ManagedPatch;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.osgi.framework.Version;

/**
 * Data-layer service that uses jGit to perform git operations for patch manageer
 */
public class GitPatchRepositoryImpl implements GitPatchRepository {

    public static final String MAIN_GIT_REPO_LOCATION = ".management/history";

    private static final Pattern BASELINE_TAG_PATTERN = Pattern.compile("^baseline-(.+)$");

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
    public void open() throws IOException {
        gitPatchManagement = new File(patchesDir, MAIN_GIT_REPO_LOCATION);
        if (!gitPatchManagement.exists()) {
            gitPatchManagement.mkdirs();
        }

        tmpPatchManagement = new File(patchesDir, "tmp");
        if (tmpPatchManagement.exists()) {
            recursiveDelete(tmpPatchManagement);
        }
        tmpPatchManagement.mkdirs();

        findOrCreateMainGitRepository();
    }


    /**
     * Recursively deletes the given file whether its a file or directory returning the number
     * of files deleted
     */
    public static int recursiveDelete(File file) {
        int answer = 0;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    answer += recursiveDelete(child);
                }
            }
        }
        if (file.delete()) {
            answer += 1;
        }
        return answer;
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
        if (mainRepository == null) {
            mainRepository = findOrCreateGitRepository(gitPatchManagement, true);
        }
        return mainRepository;
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
                prepareCommit(fork, "[PATCH] initialization").call();
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
            fork.fetch()
                    .setRemote("origin")
                    .setTagOpt(TagOpt.FETCH_TAGS)
                    .call();
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

    @Override
    public boolean containsTag(Git git, String tagName) throws GitAPIException {
        for (Ref tag : git.tagList().call()) {
            if (tag.getName().startsWith("refs/tags/") && tag.getName().endsWith("/" + tagName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CommitCommand prepareCommit(Git git, String message) {
        return git.commit()
                .setAuthor(karafHome.getName(), "fuse@redhat.com")
                .setMessage(message);
    }

    @Override
    public void push(Git git) throws GitAPIException {
        push(git, "master");
    }

    @Override
    public void push(Git git, String branch) throws GitAPIException {
        git.push()
                .setRemote("origin")
                .setRefSpecs(new RefSpec(branch))
                .setPushTags()
                .setForce(true)
                .call();
    }

    @Override
    public List<DiffEntry> diff(Git git, RevCommit commit1, RevCommit commit2) throws GitAPIException, IOException {
        return diff(git, commit1, commit2, true);
    }

    @Override
    public List<DiffEntry> diff(Git git, RevCommit commit1, RevCommit commit2, boolean showNameAndStatusOnly)
            throws GitAPIException, IOException {
        ObjectReader reader = git.getRepository().newObjectReader();

        CanonicalTreeParser ctp1 = new CanonicalTreeParser();
        CanonicalTreeParser ctp2 = new CanonicalTreeParser();
        if (commit1.getTree() == null) {
            commit1 = new RevWalk(git.getRepository()).parseCommit(commit1);
        }
        if (commit2.getTree() == null) {
            commit2 = new RevWalk(git.getRepository()).parseCommit(commit2);
        }

        ctp1.reset(reader, commit1.getTree());
        ctp2.reset(reader, commit2.getTree());

        return git.diff()
                .setShowNameAndStatusOnly(showNameAndStatusOnly)
                .setOldTree(ctp1)
                .setNewTree(ctp2)
                .call();
    }

    /**
     * <p>We have two methods of finding latest tag for baseline - sort tag names by version (not lexicographic!) or iterate down
     * the <code>master</code> branch and check the latest commit that has a <code>baseline-VERSION</code> tag.
     * We could also look by commit message pattern, but this isn't cool.</p>
     * <p>Current implementation: sort tags by VERSION (from <code>baseline-VERSION</code> of tag name)</p>
     * @param git
     * @return
     */
    @Override
    public RevTag findLatestBaseline(Git git) throws GitAPIException, IOException {
        List<Ref> tags = git.tagList().call();
        // set of tag versions in reversed order (highest version first)
        Map<Version, Ref> versions = new TreeMap<Version, Ref>(new Comparator<Version>() {
            @Override
            public int compare(Version o1, Version o2) {
                return o2.compareTo(o1);
            }
        });
        for (Ref tag : tags) {
            String name = tag.getName();
            name = name.substring(name.lastIndexOf('/') + 1);
            Matcher matcher = BASELINE_TAG_PATTERN.matcher(name);
            if (matcher.matches()) {
                versions.put(new Version(matcher.group(1)), tag);
            }
        }

        if (versions.size() > 0) {
            Ref latest = versions.values().iterator().next();
            return new RevWalk(git.getRepository()).parseTag(latest.getObjectId());
        } else {
            return null;
        }
    }

    @Override
    public RevTag findCurrentBaseline(Git git) throws GitAPIException, IOException {
        List<Ref> tags = git.tagList().call();
        RevWalk walk = new RevWalk(git.getRepository());
        Map<ObjectId, RevTag> tagMap = new HashMap<>();
        for (Ref tag : tags) {
            Ref peeled = git.getRepository().peel(tag);
            if (peeled.getPeeledObjectId() != null) {
                tagMap.put(peeled.getPeeledObjectId(), walk.parseTag(tag.getObjectId()));
            } else {
                tagMap.put(peeled.getObjectId(), walk.parseTag(tag.getObjectId()));
            }
        }

        Iterable<RevCommit> log = git.log().add(git.getRepository().resolve("master")).call();
        for (RevCommit rc : log) {
            if (tagMap.containsKey(rc.getId())) {
                return tagMap.get(rc.getId());
            }
        }

        return null;
    }

    @Override
    public ManagedPatch getManagedPatch(String id) throws IOException {
        // basic ManagedPatch information is only commit id of the relevant branch name
        ObjectId commitId = mainRepository.getRepository().resolve("refs/heads/" + id);
        if (commitId == null) {
            // this patch is not tracked (yet?)
            return null;
        }

        ManagedPatch mp = new ManagedPatch();
        mp.setPatchId(id);
        mp.setCommitId(commitId.getName());

        return mp;
    }

}
