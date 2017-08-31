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
package io.fabric8.git.internal;

import io.fabric8.api.Profiles;
import io.fabric8.api.commands.GitVersion;
import io.fabric8.common.util.Strings;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CheckoutResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import io.fabric8.api.gravia.IllegalStateAssertion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A bunch of helper methods for working with Git
 */
public class GitHelpers {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHelpers.class);

    static final String CONFIGS = "fabric";
    static final String CONFIGS_PROFILES = CONFIGS + File.separator + "profiles";
    static final String VERSION_ATTRIBUTES = "version.attributes";
    static final String REMOTE_ORIGIN = "origin";
    static final String MASTER_BRANCH = "master";
    static final String ROOT_TAG = "root";

    private static final DateFormat TIMESTAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    static final Pattern ENSEMBLE_PROFILE_PATTERN = Pattern.compile("fabric-ensemble-[0-9]+|fabric-ensemble-[0-9]+-[0-9]+");
    
    /**
     * Returns the root directory of the git repo which contains the ".git" directory
     */
    public static File getRootGitDirectory(Git git) {
        return git.getRepository().getDirectory().getParentFile();
    }

    public static File getProfilesDirectory(Git git) {
        return new File(GitHelpers.getRootGitDirectory(git), CONFIGS_PROFILES);
    }

    public static  File getProfileDirectory(Git git, String profileId) {
        File profilesDirectory = getProfilesDirectory(git);
        String path = convertProfileIdToDirectory(profileId);
        return new File(profilesDirectory, path);
    }

    /**
     * Takes a profile ID of the form "foo-bar" and if we are using directory trees for profiles then
     * converts it to "foo/bar.profile"
     */
    public static String convertProfileIdToDirectory(String profileId) {
        return Profiles.convertProfileIdToPath(profileId);
    }

    public static boolean localBranchExists(Git git, String branch) throws GitAPIException {
        List<Ref> list = git.branchList().call();
        String fullName = "refs/heads/" + branch;
        boolean localBranchExists = false;
        for (Ref ref : list) {
            String name = ref.getName();
            if (equals(name, fullName)) {
                localBranchExists = true;
                break;
            }
        }
        return localBranchExists;
    }

    public static String currentBranch(Git git) {
        String branch = null;
        Exception gitException = null;
        try {
            branch = git.getRepository().getBranch();
        } catch (Exception ex) {
            gitException = ex;
        }
        if (branch == null || gitException != null) {
            throw new IllegalStateException("Failed to get the current branch", gitException);
        }
        return branch;
    }


    public static boolean hasGitHead(Git git) throws GitAPIException, IOException {
        boolean hasHead = true;
        try {
            git.log().all().call();
            hasHead = git.getRepository().getAllRefs().containsKey("HEAD");
        } catch (NoHeadException e) {
            hasHead = false;
        }
        return hasHead;
    }

    public static void createOrCheckoutBranch(Git git, String branch, String remote) throws GitAPIException {
        createOrCheckoutBranch(git, branch, remote, Constants.HEAD);
    }

    public static void createOrCheckoutBranch(Git git, String branch, String remote, String startPoint) throws GitAPIException {
        Ref ref = null;
        String current = currentBranch(git);
        if (!equals(current, branch) && !localBranchExists(git, branch) ) {
            ref = git.checkout().setName(branch).setForce(true).setCreateBranch(true).call();
            if (remote != null) {
                configureBranch(git, branch, remote);
            }
        } else {
            ref = git.checkout().setName(branch).setForce(true).call();
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Checked out branch " + branch + " with results " + ref.getName());
        }
    }

    public static boolean checkoutBranch(Git git, String branch) throws GitAPIException {
        String current = currentBranch(git);
        if (equals(current, branch)) {
            return true;
        } else if (localBranchExists(git, branch)) {
            CheckoutCommand checkoutCommand = git.checkout().setName(branch).setForce(true);
            Ref ref = checkoutCommand.call();
            CheckoutResult result = checkoutCommand.getResult();
            LOGGER.debug("Checked out branch {} with results: {}", branch, result.getStatus());
            return true;
        } else {
            LOGGER.debug("Branch {} not found!", branch);
            return false;
        }
    }

    public static boolean checkoutTag(Git git, String tagName) throws GitAPIException {
        git.checkout().setName(tagName).setForce(true).call();
        LOGGER.debug("Checked out tag: {}", tagName);
        return true;
    }

    public static boolean removeBranch(Git git, String branch) throws GitAPIException {
        IllegalStateAssertion.assertFalse("master".equals(branch), "Cannot remove master branch");
        if (localBranchExists(git, branch)) {
            String current = currentBranch(git);
            if (equals(current, branch)) {
                checkoutBranch(git, "master");
            }
            List<String> list = git.branchDelete().setBranchNames(branch).setForce(true).call();
            LOGGER.debug("Deleted branch {} with results: {}", branch, list);
            return true;
        } else {
            LOGGER.debug("Branch {} not found!", branch);
            return true;
        }
    }

    protected static void configureBranch(Git git, String branch, String remote) {
        // lets update the merge config
        if (Strings.isNotBlank(branch)) {
            StoredConfig config = git.getRepository().getConfig();
            if (Strings.isNullOrBlank(config.getString("branch", branch, "remote")) || Strings.isNullOrBlank(
                    config.getString("branch", branch, "merge"))) {
                config.setString("branch", branch, "remote", remote);
                config.setString("branch", branch, "merge", "refs/heads/" + branch);
                try {
                    config.save();
                } catch (IOException e) {
                    LOGGER.error("Failed to configure the branch configuration to " + getRootGitDirectory(git)
                            + " with branch " + branch + " on remote repo: " + remote + ". " + e, e);
                }
            }
        }
    }

    static boolean equals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }

    /**
     * Returns the git branch of the given profile identity.
     */
    public static String getProfileBranch(String versionId, String profileId) {
        if (profileId != null && ENSEMBLE_PROFILE_PATTERN.matcher(profileId).matches()) {
            return MASTER_BRANCH;
        } else {
            return versionId;
        }
    }

    /**
     * Fetches the content of a file from Git repository without checking out the branch
     * @param git
     * @param branch short branch name
     * @param fileName name of the file to fetch
     * @param onlyFromTheCommit if <code>true</code>, return content only if the file is different than in parent commit(s)
     * @return content of file or <code>null</code> if no such file exists
     */
    public static byte[] getContentOfObject(Git git, String branch, String fileName, boolean onlyFromTheCommit) throws IOException {
        Ref ref = git.getRepository().getRef("refs/heads/" + branch);
        if (ref == null) {
            return null;
        }
        RevCommit rw = new RevWalk(git.getRepository()).parseCommit(ref.getObjectId());
        ObjectId objectId = objectIdOfResource(git, rw, fileName);
        if (objectId != null) {
            if (!onlyFromTheCommit) {
                ObjectLoader loader = git.getRepository().open(objectId);
                return loader.getBytes();
            } else {
                // if the objectId is the same as in *all* parent commits, than this resource is actually
                // not created in the branch itself - it comes from parent branch (version)
                RevCommit[] parents = rw.getParents();
                boolean change = false;
                if (parents != null && parents.length > 0) {
                    for (RevCommit parent : parents) {
                        RevCommit prc = new RevWalk(git.getRepository()).parseCommit(parent.getId());
                        ObjectId parentObjectId = objectIdOfResource(git, prc, fileName);
                        if (parentObjectId == null || !parentObjectId.equals(objectId)) {
                            change = true;
                            break;
                        }
                    }
                } else {
                    change = true;
                }
                return change ? git.getRepository().open(objectId).getBytes() : null;
            }
        } else {
            return null;
        }
    }

    private static ObjectId objectIdOfResource(Git git, RevCommit rw, String fileName) throws IOException {
        TreeWalk tw = new TreeWalk(git.getRepository());
        if (rw.getTree() == null)
            return null;
        tw.addTree(rw.getTree());
        tw.setRecursive(true);
        tw.setFilter(PathFilter.create(fileName));
        if (tw.next()) {
            return tw.getObjectId(0);
        } else {
            return null;
        }
    }

    /**
     * Fetches summary information (list of {@link GitVersion}) for given {@link Git} instance.
     * @param git
     * @return
     * @throws GitAPIException
     * @throws IOException
     */
    public static List<GitVersion> gitVersions(Git git) throws GitAPIException, IOException {
        List<Ref> refs = git.branchList().call();
        List<GitVersion> localVersions = new LinkedList<>();
        for (Ref ref : refs) {
            String v = ref.getName();
            if (v.startsWith("refs/heads/")) {
                String name = v.substring(("refs/heads/").length());
                if (name.startsWith("patch-") || name.startsWith("patches-")
                        || name.startsWith("container-history")) {
                    continue;
                }
                GitVersion gv = new GitVersion(name);
                gv.setSha1(ref.getObjectId().getName());
                RevCommit headCommit = new RevWalk(git.getRepository()).parseCommit(ref.getObjectId());
                if (headCommit != null) {
                    gv.setMessage(headCommit.getShortMessage());
                    gv.setTimestamp(TIMESTAMP.format(new Date(headCommit.getCommitTime() * 1000L)));
                }
                localVersions.add(gv);
            }
        }
        return localVersions;
    }

}
