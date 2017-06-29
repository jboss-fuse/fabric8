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
package io.fabric8.git;

import io.fabric8.api.GitContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;

public interface PullPushPolicy {

    /**
     * Result of fetching state from central fabric git repository to local git repository
     */
    interface PullPolicyResult {

        /**
         * Names of local branches/versions that were updated (removed, created or their HEAD changed by fast-forward
         * or rebase)
         * @return
         */
        Map<String, BranchChange> localUpdateVersions();

        /**
         * Whether {@link PullPushPolicy#doPush(GitContext, CredentialsProvider)} is needed after pulling
         * @return
         */
        boolean remoteUpdateRequired();

        /**
         * Names of all branches/versions found remotely (even if local branches are in sync), without locally
         * deleted branches
         * @return
         */
        Set<String> getVersions();

        Exception getLastException();
    }

    /**
     * Result of pushing state from local git repository to central fabric git repository
     */
    interface PushPolicyResult {

        List<PushResult> getPushResults();

        Map<String, RemoteBranchChange> getAcceptedUpdates();

        Map<String, RemoteBranchChange> getRejectedUpdates();

        Exception getLastException();
    }

    /**
     * Indication of a change performed to local branch - was it created? deleted? updated and how?
     */
    public static class BranchChange {

        protected String branch;
        protected Change change = Change.UP_TO_DATE;
        protected ObjectId ref1, ref2;
        protected String updateStatus;

        public BranchChange(String branch) {
            this.branch = branch;
        }

        public BranchChange created() {
            change = Change.CREATED;
            return this;
        }

        public BranchChange removed() {
            change = Change.REMOVED;
            return this;
        }

        public BranchChange updated(ObjectId previousRef, ObjectId newRef, String how) {
            change = Change.UPDATED;
            ref1 = previousRef;
            ref2 = newRef;
            updateStatus = how;
            return this;
        }

        @Override
        public String toString() {
            if (change == Change.UPDATED) {
                return String.format("%s%s", branch, String.format(change.description, updateStatus,
                        ref1 == null ? "?" : ref1.getName(), ref2 == null ? "?" : ref2.getName()));
            } else {
                return String.format("%s%s", branch, change.description);
            }
        }

        static enum Change {
            CREATED("(new branch)"),
            REMOVED("(deleted)"),
            UPDATED("(updated by %s: %s..%s)"),
            REJECTED("(rejected due to %s: %s..%s)"),
            UP_TO_DATE("(no change)");
            private String description;

            Change(String description) {
                this.description = description;
            }
        }
    }

    /**
     * Indication of a change performed to remote branch.
     */
    public static class RemoteBranchChange extends BranchChange {

        private final RemoteRefUpdate remoteRefUpdate;

        public RemoteBranchChange(String branch, RemoteRefUpdate remoteRefUpdate) {
            super(branch);
            this.remoteRefUpdate = remoteRefUpdate;
        }

        @Override
        public RemoteBranchChange updated(ObjectId previousRef, ObjectId newRef, String how) {
            return (RemoteBranchChange) super.updated(previousRef, newRef, how);
        }

        public RemoteBranchChange rejected(ObjectId previousRef, ObjectId newRef, String why) {
            this.change = Change.REJECTED;
            this.ref1 = previousRef;
            this.ref2 = newRef;
            this.updateStatus = why;
            return this;
        }

        public RemoteRefUpdate getRemoteRefUpdate() {
            return remoteRefUpdate;
        }

        @Override
        public String toString() {
            if (change == Change.REJECTED) {
                return String.format("%s%s", branch, String.format(change.description, updateStatus,
                        ref1 == null ? "?" : ref1.getName(), ref2 == null ? "?" : ref2.getName()));
            } else {
                return super.toString();
            }
        }

    }

    /**
     * Pull the version/profile state from the remote repository
     */
    PullPolicyResult doPull(GitContext context, CredentialsProvider credentialsProvider, boolean allowVersionDelete);

    /**
     * Push the version/profile state to the remote repository
     */
    PushPolicyResult doPush(GitContext context, CredentialsProvider credentialsProvider);

}
