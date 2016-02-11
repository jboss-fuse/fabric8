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
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric8.patch.management.EnvType;
import io.fabric8.patch.management.ManagedPatch;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.eclipse.jgit.api.CheckoutCommand;
import io.fabric8.patch.management.io.NtfsAwareCheckoutCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
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
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.osgi.framework.Version;

/**
 * Data-layer service that uses jGit to perform git operations for patch manageer
 */
public class GitPatchRepositoryImpl implements GitPatchRepository {

    public static final String MAIN_GIT_REPO_LOCATION = ".management/history";

    private static final Pattern BASELINE_TAG_PATTERN = Pattern.compile("^baseline-.*(\\d.+)$");

    private final EnvType env;
    // what kind of env we're operating?
    private final boolean isFabric;

    // ${karaf.home}
    private File karafHome;
    // ${karaf.base}
    private File karafBase;
    // ${karaf.data}
    private File karafData;

    // main patches directory at ${fuse.patch.location} (defaults to ${karaf.home}/patches)
    private File patchesDir;

    // directory containing "reference", bare git repository to store patch management history
    private File gitPatchManagement;
    // main git (bare) repository at ${fuse.patch.location}/.management/history
    private Git mainRepository;

    // directory to store temporary forks and working copies to perform operations before pushing to
    // "reference" repository
    private File tmpPatchManagement;

    // main patch branch name that tracks the history of patch installations/rollbacks for current product
    private String mainPatchBranchName;

    // in fabric mode, we will have separate patch branches for root, SSH and child containers
    private String childContainerPatchBranchName;
    // history of patches for SSH containers created from unreliable distro (ZIPped Fuse)
    private String fuseSSHContainerPatchBranchName;
    // history of patches for SSH containers created from reliable (official, not ZIPped on the fly) distro
    private String fabric8SSHContainerPatchBranchName;
    // history of baselines for Fuse root container
    private String fuseRootContainerPatchBranchName;
    // history of baselines for AMQ root container
    private String amqRootContainerPatchBranchName;

    // are we master repository? (doing further pushes to origin?)
    private boolean master;

    // let's keep admin:create container's ID here
    private String standaloneChildkarafName;

    /**
     * @param env
     * @param patchRepositoryLocation
     * @param karafHome
     * @param karafBase
     * @param karafData
     * @param patchesDir
     */
    public GitPatchRepositoryImpl(EnvType env, File patchRepositoryLocation,
                                  File karafHome, File karafBase, File karafData, File patchesDir) {
        this.env = env;
        this.isFabric = env.isFabric();

        // private branch - don't pushed anywhere outside of patches/.management/history
        // in either env, each container tracks its own history - this branch's HEAD shows how ${karaf.base} looks
        // like. In fabric mode, each container's history branch starts with copy of particular baseline tag
        // and have custom changes on top. When fabric-agent detects the upgrade/downgrade, it rebuilds the
        // container history from another baseline
        this.mainPatchBranchName = GitPatchRepository.HISTORY_BRANCH;
        if (env == EnvType.STANDALONE_CHILD) {
            String suffix = "";
            try {
                Properties systemProperties = new Properties();
                try (FileInputStream fis = new FileInputStream(new File(karafBase, "etc/system.properties"))) {
                    systemProperties.load(fis);
                    standaloneChildkarafName = systemProperties.getProperty("karaf.name");
                    if (standaloneChildkarafName != null && !"".equals(standaloneChildkarafName)) {
                        suffix = "-" + standaloneChildkarafName.replace(' ', '_');
                    }
                }
            } catch (Exception e) {
                System.err.println(e.getMessage());
            }
            this.mainPatchBranchName = GitPatchRepository.ADMIN_HISTORY_BRANCH + suffix;
        }

        // in fabric mode we track history of baselines for each kind of container
        if (isFabric) {
            this.childContainerPatchBranchName = "patches-child";
            this.fuseSSHContainerPatchBranchName = "patches-ssh-fuse";
            this.fabric8SSHContainerPatchBranchName = "patches-ssh-fabric8";
            this.fuseRootContainerPatchBranchName = "patches-root-fuse";
            this.amqRootContainerPatchBranchName = "patches-root-amq";
        } else {
            this.childContainerPatchBranchName = "patches-admin-child";
        }

        this.gitPatchManagement = patchRepositoryLocation;
        this.karafHome = karafHome;
        this.karafBase = karafBase;
        this.karafData = karafData;
        this.patchesDir = patchesDir;
    }

    @Override
    public void open() throws IOException, GitAPIException {
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
    public Git findOrCreateMainGitRepository() throws IOException, GitAPIException {
        if (mainRepository == null) {
            mainRepository = findOrCreateGitRepository(gitPatchManagement, true);
        }

        if (isFabric) {
            // we connect patch repo with fabric repo
            StoredConfig config = mainRepository.getRepository().getConfig();
            if (config.getString("remote", "origin", "url") == null) {
                File origin = new File(karafData, "git/local/fabric");
                config.setString("remote", "origin", "url", origin.getCanonicalPath());
                config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
                config.save();
            }
        }

        if (env == EnvType.STANDALONE_CHILD) {
            // let's "register" our private branch in root's patch management git repository
            if (mainRepository.getRepository().getRef("refs/heads/" + getMainBranchName()) == null) {
                String startPoint = "patch-management^{commit}";
                mainRepository.branchCreate()
                        .setName(getMainBranchName())
                        .setStartPoint(startPoint)
                        .call();
            }
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
                        .setBare(bare && !isFabric)
                        .setDirectory(directory)
                        .call();

                // first commit - it's in master branch
                Git fork = cloneRepository(git, false);
                RevCommit commit = prepareCommit(fork, "[PATCH] initialization").call();
                fork.tag()
                        .setName("patch-management")
                        .setObjectId(commit)
                        .call();

                if (!"master".equals(getMainBranchName())) {
                    fork.checkout()
                            .setName(getMainBranchName())
                            .setStartPoint("master")
                            .setCreateBranch(true)
                            .call();
                }
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
                    .setName(getMainBranchName())
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .setStartPoint("origin/" + getMainBranchName())
                    .call();
        }

        return fork;
    }

    @Override
    public void closeRepository(Git git, boolean deleteWorkingCopy) {
        git.getRepository().close();
        if (deleteWorkingCopy) {
            FileUtils.deleteQuietly(git.getRepository().getDirectory().getParentFile());
        }
    }

    @Override
    public CheckoutCommand checkout(Git git) {
        return new NtfsAwareCheckoutCommand(git.getRepository());
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
        push(git, getMainBranchName());
    }

    @Override
    public void push(Git git, String branch) throws GitAPIException {
        git.push()
                .setRemote("origin")
                .setRefSpecs(new RefSpec(branch))
                .setPushTags()
                .setForce(true)
                .call();

        // in fabric env we synchronize changes with "local" git repo, which will be then (from fabric-agent)
        // pushed further to cluster's git repo
        if (master && mainRepository != null
                && !branch.startsWith("patch-")
                && !branch.equals(getMainBranchName())) {
            // we don't push "patch-*" branches to central repo (we push "patches-*")
            // getMainBranchName() is used to track current container's history
            mainRepository.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec(branch))
                    .setPushTags()
                    .setForce(true)
                    .call();
        }
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
     * the main patch branch and check the latest commit that has a <code>baseline-VERSION</code> tag.
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
        Map<ObjectId, List<RevTag>> tagMap = new HashMap<>();
        for (Ref tag : tags) {
            Ref peeled = git.getRepository().peel(tag);
            RevTag revTag = walk.parseTag(tag.getObjectId());
            if (!BASELINE_TAG_PATTERN.matcher(revTag.getTagName()).matches()) {
                continue;
            }
            ObjectId key = null;
            if (peeled.getPeeledObjectId() != null) {
                key = peeled.getPeeledObjectId();
            } else {
                key = peeled.getObjectId();
            }
            if (!tagMap.containsKey(key)) {
                tagMap.put(key, new LinkedList<RevTag>());
            }
            tagMap.get(key).add(revTag);
        }

        Iterable<RevCommit> log = git.log().add(git.getRepository().resolve(getMainBranchName())).call();
        for (RevCommit rc : log) {
            if (tagMap.containsKey(rc.getId())) {
                if (tagMap.get(rc.getId()).size() == 1) {
                    return tagMap.get(rc.getId()).get(0);
                } else {
                    // we may assume there's tag from standalone baseline and from fabric baseline
                    // standalone may look like "baseline-6.2.1.xxx-NNN"
                    // fabric may look like "baseline-root-fuse-6.2.1.xxx-NNN"
                    // so we can do simplest thing - select tag with longer name...
                    RevTag result = null;
                    for (RevTag t : tagMap.get(rc.getId())) {
                        if (result == null || result.getTagName().length() < t.getTagName().length()) {
                            result = t;
                        }
                    }
                    return result;
                }
            }
        }

        return null;
    }

    @Override
    public ManagedPatch getManagedPatch(String id) throws IOException {
        // basic ManagedPatch information is only commit id of the relevant branch name
        ObjectId commitId = mainRepository.getRepository().resolve("refs/heads/patch-" + id);
        if (commitId == null) {
            // this patch is not tracked (yet?)
            return null;
        }

        ManagedPatch mp = new ManagedPatch();
        mp.setPatchId(id);
        mp.setCommitId(commitId.getName());

        return mp;
    }

    @Override
    public Map<String, RevTag> findTagsBetween(Git git, RevCommit c1, RevCommit c2) throws GitAPIException, IOException {
        Map<ObjectId, List<RevTag>> reverseReferences = new HashMap<>();
        RevWalk walk = new RevWalk(git.getRepository());
        for (Ref t : git.tagList().call()) {
            Ref peeled = git.getRepository().peel(t);
            if (peeled != null && peeled.getPeeledObjectId() != null) {
                if (!reverseReferences.containsKey(peeled.getPeeledObjectId())) {
                    reverseReferences.put(peeled.getPeeledObjectId(), new LinkedList<RevTag>());
                }
                reverseReferences.get(peeled.getPeeledObjectId()).add(walk.parseTag(t.getObjectId()));
            }
        }
        Map<String, RevTag> result = new HashMap<>();
        Iterable<RevCommit> commits = git.log().addRange(c1, c2).call();
        for (RevCommit commit : commits) {
            if (reverseReferences.containsKey(commit.getId())) {
                for (RevTag tag : reverseReferences.get(commit.getId())) {
                    result.put(tag.getTagName(), tag);
                }
            }
        }
        return result;
    }

    @Override
    public String getFileContent(Git fork, String sha1, String fileName) throws IOException {
        ObjectReader objectReader = fork.getRepository().newObjectReader();
        RevCommit commit = new RevWalk(fork.getRepository())
                .parseCommit(fork.getRepository().resolve(sha1));

        TreeWalk tw = new TreeWalk(fork.getRepository());
        tw.addTree(commit.getTree());
        tw.setRecursive(false);
        tw.setFilter(PathFilter.create(fileName));
        if (tw.next()) {
            ObjectId objectId = tw.getObjectId(0);
            ObjectLoader loader = fork.getRepository().open(objectId);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            loader.copyTo(out);
            return new String(out.toByteArray(), "UTF-8");
        }

        return null;
    }

    @Override
    public String getMainBranchName() {
        return mainPatchBranchName;
    }

    @Override
    public String getChildBranchName() {
        return childContainerPatchBranchName;
    }

    @Override
    public String getFuseSSHContainerPatchBranchName() {
        return fuseSSHContainerPatchBranchName;
    }

    @Override
    public String getFabric8SSHContainerPatchBranchName() {
        return fabric8SSHContainerPatchBranchName;
    }

    @Override
    public String getFuseRootContainerPatchBranchName() {
        return fuseRootContainerPatchBranchName;
    }

    @Override
    public String getAmqRootContainerPatchBranchName() {
        return amqRootContainerPatchBranchName;
    }

    @Override
    public void setMaster(boolean master) {
        this.master = master;
    }

}
