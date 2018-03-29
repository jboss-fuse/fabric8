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

import io.fabric8.api.GitContext;
import io.fabric8.api.visibility.VisibleForExternal;
import io.fabric8.git.PullPushPolicy;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.RebaseCommand.Operation;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import io.fabric8.api.gravia.IllegalStateAssertion;
import org.eclipse.jgit.transport.TagOpt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default {@link PullPushPolicy}.
 */
public final class DefaultPullPushPolicy implements PullPushPolicy  {

    private static final transient Logger LOGGER = LoggerFactory.getLogger(DefaultPullPushPolicy.class);
    private static final int MAX_MERGES_WITHOUT_GC = 100;

    private final Git git;
    private final String remoteRef;
    private final int gitTimeout;
    private final boolean gitAllowRemoteUpdate;

    private int mergesWithoutGC = MAX_MERGES_WITHOUT_GC;

    @VisibleForExternal
    public DefaultPullPushPolicy(Git git, String remoteRef, int gitTimeout) {
        this(git, remoteRef, gitTimeout, true);
    }

    @VisibleForExternal
    public DefaultPullPushPolicy(Git git, String remoteRef, int gitTimeout, boolean gitAllowRemoteUpdate) {
        this.git = git;
        this.remoteRef = remoteRef;
        this.gitTimeout = gitTimeout;
        this.gitAllowRemoteUpdate = gitAllowRemoteUpdate;
    }

    @Override
    public synchronized PullPolicyResult doPull(GitContext context, CredentialsProvider credentialsProvider, boolean allowVersionDelete) {
        return doPull(context, credentialsProvider, allowVersionDelete, gitAllowRemoteUpdate);
    }

    @Override
    public synchronized PullPolicyResult doPull(GitContext context, CredentialsProvider credentialsProvider, boolean allowVersionDelete, boolean allowPush) {
        return doPull(context, credentialsProvider, allowVersionDelete, allowPush, gitTimeout);
    }

    @Override
    public synchronized PullPolicyResult doPull(GitContext context, CredentialsProvider credentialsProvider, boolean allowVersionDelete, boolean allowPush, int timeoutInSeconds) {
        Repository repository = git.getRepository();
        StoredConfig config = repository.getConfig();
        String remoteUrl = config.getString("remote", remoteRef, "url");
        if (remoteUrl == null) {
            LOGGER.info("No remote repository defined, so not doing a pull");
            return new AbstractPullPolicyResult();
        }

        LOGGER.info("Performing a pull on remote URL: {}", remoteUrl);

        // Get local and remote branches
        Map<String, Ref> localBranches = new HashMap<String, Ref>();
        Map<String, Ref> remoteBranches = new HashMap<String, Ref>();
        Set<String> allBranches = new HashSet<String>();

        Exception lastException = null;
        try {
            // list remote branches
            // ENTESB-7704: we have to do it before actual fetching, otherwise we can get org.eclipse.jgit.errors.MissingObjectException
            // when trying to fast-forward to commit done upstream between fetch and ls-remote!
            for (Ref ref : git.lsRemote().setTimeout(timeoutInSeconds).setCredentialsProvider(credentialsProvider)
                    .setTags(false)
                    .setHeads(true)
                    .setRemote(remoteRef).call()) {
                if (ref.getName().startsWith("refs/heads/")) {
                    String name = ref.getName().substring(("refs/heads/").length());
                    remoteBranches.put(name, ref);
                    allBranches.add(name);
                }
            }

            git.fetch().setTimeout(timeoutInSeconds).setCredentialsProvider(credentialsProvider)
                    .setTagOpt(TagOpt.FETCH_TAGS)
                    .setRemote(remoteRef).call();
        } catch (Exception ex) {
            lastException = ex;
        }

        // No meaningful processing after GitAPIException
        if (lastException != null) {
            logPullException(lastException);
            return new AbstractPullPolicyResult(lastException);
        }

        try {
            // list local branches
            for (Ref ref : git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()) {
                // FABRIC-1173, ENTESB-1332 - we can't list *really* remote branches by listing remote references to
                // those branches from local git repo
//                if (ref.getName().startsWith("refs/remotes/" + remoteRef + "/")) {
//                    String name = ref.getName().substring(("refs/remotes/" + remoteRef + "/").length());
//                    remoteBranches.put(name, ref);
//                    allBranches.add(name);
//                }
                if (ref.getName().startsWith("refs/heads/")) {
                    String name = ref.getName().substring(("refs/heads/").length());
                    localBranches.put(name, ref);
                    allBranches.add(name);
                }
            }

            Map<String, BranchChange> localUpdate = new HashMap<>();
            boolean remoteUpdate = false;
            Set<String> versions = new TreeSet<>();

            // Remote repository has no branches, force a push
            if (remoteBranches.isEmpty()) {
                LOGGER.info("Pulled from an empty remote repository");
                return new AbstractPullPolicyResult(versions, localUpdate, !localBranches.isEmpty(), null);
            } else {
                LOGGER.debug("Processing remote branches: {}", remoteBranches);
            }

            // Verify master branch and do a checkout of it when we have it locally (already)
            IllegalStateAssertion.assertTrue(remoteBranches.containsKey(GitHelpers.MASTER_BRANCH), "Remote repository does not have a master branch");

            // Iterate over all local/remote branches
            for (String branch : allBranches) {

                // Delete a local branch that does not exist remotely, but not master
                boolean allowDelete = allowVersionDelete && !GitHelpers.MASTER_BRANCH.equals(branch);
                if (localBranches.containsKey(branch) && !remoteBranches.containsKey(branch)) {
                    if (allowDelete) {
                        String remotebranchRef = String.format("remotes/%s/%s", remoteRef, branch);
                        LOGGER.info("Deleting local branch: {} and local reference to remote branch: {}", branch, remotebranchRef);
                        // we can't delete (even with --force) a branch that's checked out - let's checkout master then
                        // which can't be deleted
                        if (branch.equals(git.getRepository().getBranch())) {
                            git.clean().setCleanDirectories(true).call();             // to remove not-tracked files
                            git.reset().setMode(ResetCommand.ResetType.MIXED).call(); // for file permissions
                            git.reset().setMode(ResetCommand.ResetType.HARD).call();  // for other changes
                            git.checkout().setName("master").call();
                        }
                        git.branchDelete().setBranchNames(branch, remotebranchRef).setForce(true).call();
                        localUpdate.put(branch, new BranchChange(branch).removed());
                    } else {
                        remoteUpdate = true;
                    }
                }

                // Create a local branch that exists remotely
                else if (!localBranches.containsKey(branch) && remoteBranches.containsKey(branch)) {
                    LOGGER.info("Adding new local branch: {}", branch);
                    git.checkout().setCreateBranch(true).setName(branch).setStartPoint(remoteRef + "/" + branch).setUpstreamMode(SetupUpstreamMode.TRACK).setForce(true).call();
                    versions.add(branch);
                    localUpdate.put(branch, new BranchChange(branch).created());
                }

                // Update a local branch that also exists remotely
                else if (localBranches.containsKey(branch) && remoteBranches.containsKey(branch)) {
                    ObjectId localObjectId = localBranches.get(branch).getObjectId();
                    ObjectId remoteObjectId = remoteBranches.get(branch).getObjectId();
                    String localCommit = localObjectId.getName();
                    String remoteCommit = remoteObjectId.getName();
                    if (!localCommit.equals(remoteCommit)) {
                        git.clean().setCleanDirectories(true).call();
                        git.checkout().setName(branch).setForce(true).call();
                        MergeResult mergeResult = git.merge().setFastForward(FastForwardMode.FF_ONLY).include(remoteObjectId).call();
                        MergeStatus mergeStatus = mergeResult.getMergeStatus();
                        LOGGER.info("Updating local branch {} with status: {} ({}..{})",
                                branch, mergeStatus, localCommit, remoteCommit);
                        if (mergeStatus == MergeStatus.FAST_FORWARD) {
                            localUpdate.put(branch, new BranchChange(branch).updated(localObjectId, remoteObjectId, "fast forward"));
                        } else if (mergeStatus == MergeStatus.ALREADY_UP_TO_DATE) {
                            if (allowPush) {
                                LOGGER.info("Remote branch {} is behind local version - changes will be pushed", branch);
                                remoteUpdate = true;
                            } else {
                                LOGGER.info("Remote branch {} is behind local version - changes won't be pushed - restoring remote tracking branch", branch);
                                GitHelpers.createOrCheckoutBranch(git, GitHelpers.MASTER_BRANCH, GitHelpers.REMOTE_ORIGIN);
                                git.branchDelete().setBranchNames(branch).setForce(true).call();
                                git.checkout().setCreateBranch(true).setName(branch).setStartPoint(remoteRef + "/" + branch).setUpstreamMode(SetupUpstreamMode.TRACK).setForce(true).call();
                                localUpdate.put(branch, new BranchChange(branch).updated(localObjectId, remoteObjectId, "reset"));
                            }
                        } else if (mergeStatus == MergeStatus.ABORTED) {
                            // failure to merge using FastForwardMode.FF_ONLY always ends with MergeStatus.ABORTED
                            RebaseResult.Status rebaseStatus = null;
                            if (allowPush) {
                                LOGGER.info("Cannot fast forward branch {}, attempting rebase", branch);
                                RebaseResult rebaseResult = git.rebase().setUpstream(remoteCommit).call();
                                rebaseStatus = rebaseResult.getStatus();
                            }
                            if (rebaseStatus == RebaseResult.Status.OK) {
                                LOGGER.info("Rebase successful for branch {}", branch);
                                localUpdate.put(branch, new BranchChange(branch).updated(localObjectId, remoteObjectId, "rebase"));
                                remoteUpdate = true;
                            } else {
                                if (allowPush) {
                                    LOGGER.warn("Rebase on branch {} failed, restoring remote tracking branch", branch);
                                    git.rebase().setOperation(Operation.ABORT).call();
                                } else {
                                    LOGGER.info("Restoring remote tracking branch {}", branch);
                                }
                                GitHelpers.createOrCheckoutBranch(git, GitHelpers.MASTER_BRANCH, GitHelpers.REMOTE_ORIGIN, remoteCommit);
                                git.branchDelete().setBranchNames(branch).setForce(true).call();
                                git.checkout().setCreateBranch(true).setName(branch).setStartPoint(remoteRef + "/" + branch).setUpstreamMode(SetupUpstreamMode.TRACK).setForce(true).call();
                                localUpdate.put(branch, new BranchChange(branch).updated(localObjectId, remoteObjectId, "reset"));
                            }
                        }
                    } else if (!git.status().call().isClean()) {
                        LOGGER.info("Local branch {} is up to date, but not clean. Cleaning working copy now.", branch);
                        git.clean().setCleanDirectories(true).call();             // to remove not-tracked files
                        git.reset().setMode(ResetCommand.ResetType.MIXED).call(); // for file permissions
                        git.reset().setMode(ResetCommand.ResetType.HARD).call();  // for other changes
                    }
                    versions.add(branch);
                }
            }
            if (localUpdate.size() > 0) {
                if (--mergesWithoutGC < 0) {
                    mergesWithoutGC = MAX_MERGES_WITHOUT_GC;
                    LOGGER.info("Performing 'git gc' after {} merges", MAX_MERGES_WITHOUT_GC);
                    try {
                        git.gc().setAggressive(true).call();
                    } catch (Exception e) {
                        LOGGER.warn("Problem invoking 'git gc': {}", e.getMessage());
                    }
                }
            }

            PullPolicyResult result = new AbstractPullPolicyResult(versions, localUpdate, remoteUpdate, null);
            LOGGER.info("Pull result: {}", result);
            return result;
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage(), ex);
            return new AbstractPullPolicyResult(ex);
        }
    }

    /**
     * Print appropriate exception message when doing <em>pull</em> operation (including ls-remote, fetch, ...)
     * @param exception
     */
    private void logPullException(Throwable exception) {
        Throwable t = exception;
        while (t != null) {
            if (t instanceof InvalidRemoteException
                    || t instanceof NoRemoteRepositoryException
                    || t instanceof ConnectException
                    || t instanceof SocketTimeoutException) {
                LOGGER.warn("Pull failed during fetch because remote repository is not ready yet (pull will be retried): {}", t.getMessage());
                return;
            }
            t = t.getCause();
        }

        LOGGER.warn("Pull failed during fetch because of: " + exception.getMessage(), exception);
    }

    @Override
    public synchronized PushPolicyResult doPush(GitContext context, CredentialsProvider credentialsProvider) {

        StoredConfig config = git.getRepository().getConfig();
        String remoteUrl = config.getString("remote", remoteRef, "url");
        if (remoteUrl == null) {
            LOGGER.debug("No remote repository defined, so not doing a push");
            return new AbstractPushPolicyResult();
        }

        LOGGER.info("Pushing last change to: {}", remoteUrl);

        Iterator<PushResult> resit = null;
        Exception lastException = null;
        try {
            // clean working copy before the push
            git.clean().setCleanDirectories(true).call();             // to remove not-tracked files
            git.reset().setMode(ResetCommand.ResetType.MIXED).call(); // for file permissions
            git.reset().setMode(ResetCommand.ResetType.HARD).call();  // for other changes

            resit = git.push().setTimeout(gitTimeout).setCredentialsProvider(credentialsProvider)
                    .setPushTags()
                    .setPushAll()
                    .call().iterator();
        } catch (Exception ex) {
            lastException = ex;
        }

        // Allow the commit to stay in the repository in case of push failure
        if (lastException != null) {
            LOGGER.warn("Cannot push because of: " + lastException.toString(), lastException);
            return new AbstractPushPolicyResult(lastException);
        }

        List<PushResult> pushResults = new ArrayList<>();
        Map<String, RemoteBranchChange> acceptedUpdates = new TreeMap<>();
        Map<String, RemoteBranchChange> rejectedUpdates = new TreeMap<>();

        // Collect the updates that are not ok
        while (resit.hasNext()) {
            PushResult pushResult = resit.next();
            pushResults.add(pushResult);
            for (RemoteRefUpdate refUpdate : pushResult.getRemoteUpdates()) {
                Status status = refUpdate.getStatus();
                ObjectId from = refUpdate.getTrackingRefUpdate() == null || refUpdate.getTrackingRefUpdate().getOldObjectId() == null
                        ? refUpdate.getExpectedOldObjectId() : refUpdate.getTrackingRefUpdate().getOldObjectId();
                ObjectId to = refUpdate.getTrackingRefUpdate() == null || refUpdate.getTrackingRefUpdate().getNewObjectId() == null
                        ? refUpdate.getNewObjectId() : refUpdate.getTrackingRefUpdate().getNewObjectId();
                if (status == Status.OK) {
                    acceptedUpdates.put(refUpdate.getSrcRef(), new RemoteBranchChange(refUpdate.getSrcRef(), refUpdate)
                            .updated(from, to, "fast-forward"));
                } else if (status == Status.UP_TO_DATE) {
                    acceptedUpdates.put(refUpdate.getSrcRef(), new RemoteBranchChange(refUpdate.getSrcRef(), refUpdate));
                } else {
                    switch (status) {
                        case REJECTED_NONFASTFORWARD:
                            // typical problem when repos are not synced
                            rejectedUpdates.put(refUpdate.getSrcRef(), new RemoteBranchChange(refUpdate.getSrcRef(), refUpdate)
                                    .rejected(from, to, "non fast-forward update"));
                            break;
                        case NOT_ATTEMPTED:
                        case REJECTED_NODELETE:
                        case REJECTED_REMOTE_CHANGED:
                        case REJECTED_OTHER_REASON:
                        case NON_EXISTING:
                        case AWAITING_REPORT:
                        default:
                            // ?
                            rejectedUpdates.put(refUpdate.getSrcRef(), new RemoteBranchChange(refUpdate.getSrcRef(), refUpdate)
                                    .rejected(from, to, status.toString()));
                            break;
                    }
                }
            }
        }

        // Reset to the last known good rev and make the commit/push fail
        for (String rejectedRefName : rejectedUpdates.keySet()) {
            RemoteRefUpdate rejectedRef = rejectedUpdates.get(rejectedRefName).getRemoteRefUpdate();
            LOGGER.warn("Rejected push: {}. Attempting to recreate local branch.", rejectedRef);
            String refName = rejectedRef.getRemoteName();
            String branch = refName.substring(refName.lastIndexOf('/') + 1);
            try {
                GitHelpers.checkoutBranch(git, branch);
                FetchResult fetchResult = git.fetch().setTimeout(gitTimeout).setCredentialsProvider(credentialsProvider).setRemote(remoteRef).setRefSpecs(new RefSpec("refs/heads/" + branch)).call();
                Ref fetchRef = fetchResult.getAdvertisedRef("refs/heads/" + branch);
                git.branchRename().setOldName(branch).setNewName(branch + "-tmp").call();
                git.checkout().setCreateBranch(true).setName(branch).setStartPoint(fetchRef.getObjectId().getName()).call();
                git.branchDelete().setBranchNames(branch + "-tmp").setForce(true).call();
                LOGGER.info("Local branch {} recreated from {}", branch, fetchRef.toString());
            } catch (Exception ex) {
                LOGGER.warn("Cannot recreate branch " + branch + " because of: " + ex.toString(), ex);
            }
        }

        PushPolicyResult result = new AbstractPushPolicyResult(pushResults, acceptedUpdates, rejectedUpdates, null);
        LOGGER.info("Push result: {}", result);
        return result;
    }

    static class AbstractPullPolicyResult implements PullPolicyResult {

        private final Set<String> versions = new TreeSet<>();
        private final Map<String, BranchChange> localUpdate = new HashMap<>();
        private final boolean remoteUpdate;
        private final Exception lastException;

        AbstractPullPolicyResult() {
            this(Collections.<String>emptySet(), Collections.<String, BranchChange>emptyMap(), false, null);
        }

        AbstractPullPolicyResult(Exception lastException) {
            this(Collections.<String>emptySet(), Collections.<String, BranchChange>emptyMap(), false, lastException);
        }

        AbstractPullPolicyResult(Set<String> versions, Map<String, BranchChange> localUpdate, boolean remoteUpdate, Exception lastException) {
            this.versions.addAll(versions);
            this.localUpdate.putAll(localUpdate);
            this.remoteUpdate = remoteUpdate;
            this.lastException = lastException;
        }

        @Override
        public Map<String, BranchChange> localUpdateVersions() {
            return  Collections.unmodifiableMap(localUpdate);
        }

        @Override
        public boolean remoteUpdateRequired() {
            return remoteUpdate;
        }

        @Override
        public Set<String> getVersions() {
            return Collections.unmodifiableSet(versions);
        }

        @Override
        public Exception getLastException() {
            return lastException;
        }

        @Override
        public String toString() {
            return "[localUpdate=" + localUpdate.values() + ",remoteUpdate=" + remoteUpdate + ",versions=" + versions + ",error=" + lastException + "]";
        }
    }

    static class AbstractPushPolicyResult implements PushPolicyResult {

        private final List<PushResult> pushResults = new ArrayList<>();
        private final Map<String, RemoteBranchChange> acceptedUpdates = new TreeMap<>();
        private final Map<String, RemoteBranchChange> rejectedUpdates = new TreeMap<>();
        private final Exception lastException;

        AbstractPushPolicyResult() {
            this(Collections.<PushResult>emptyList(), Collections.<String, RemoteBranchChange>emptyMap(), Collections.<String, RemoteBranchChange>emptyMap(), null);
        }

        AbstractPushPolicyResult(Exception lastException) {
            this(Collections.<PushResult>emptyList(), Collections.<String, RemoteBranchChange>emptyMap(), Collections.<String, RemoteBranchChange>emptyMap(), lastException);
        }

        AbstractPushPolicyResult(List<PushResult> pushResults, Map<String, RemoteBranchChange> acceptedUpdates, Map<String, RemoteBranchChange> rejectedUpdates, Exception lastException) {
            this.pushResults.addAll(pushResults);
            this.acceptedUpdates.putAll(acceptedUpdates);
            this.rejectedUpdates.putAll(rejectedUpdates);
            this.lastException = lastException;
        }

        @Override
        public List<PushResult> getPushResults() {
            return Collections.unmodifiableList(pushResults);
        }

        @Override
        public Map<String, RemoteBranchChange> getAcceptedUpdates() {
            return Collections.unmodifiableMap(acceptedUpdates);
        }

        @Override
        public Map<String, RemoteBranchChange> getRejectedUpdates() {
            return Collections.unmodifiableMap(rejectedUpdates);
        }

        @Override
        public Exception getLastException() {
            return lastException;
        }

        @Override
        public String toString() {
            return "[accepted=" + acceptedUpdates.values() + ",rejected=" + rejectedUpdates.values() + ",error=" + lastException + "]";
        }
    }

}
