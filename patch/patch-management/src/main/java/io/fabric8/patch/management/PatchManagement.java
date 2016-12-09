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
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;

/**
 * <p>Interface to an OSGi service that can handle low-level patch management operations.</p>
 * <p>To be used by patch:* commands when interaction with patch data is required.</p>
 */
public interface PatchManagement {

    /**
     * Load all available patches. {@link Patch#getManagedPatch()} returns only basic information.
     * To get more details, use {@link #loadPatch(PatchDetailsRequest)} with specific details.
     * @param details whether to retrieve {@link ManagedPatch} information
     * @return
     * @throws PatchException
     */
    List<Patch> listPatches(boolean details) throws PatchException;

    /**
     * Load {@link Patch} with required details level, specified in {@link PatchDetailsRequest}.
     * @param request
     * @return
     */
    Patch loadPatch(PatchDetailsRequest request) throws PatchException;

    /**
     * Retriees an artifact from a given URL and returns list of patch data it contains (*.zip) or it is (*.patch)
     * @param url
     * @return
     */
    List<PatchData> fetchPatches(URL url) throws PatchException;

    /**
     * Artifacts referenced from patch are uploaded to <code>URI</code>
     * @param patchData
     * @param uploadAddress
     * @param callback
     * @throws PatchException
     */
    void uploadPatchArtifacts(PatchData patchData, URI uploadAddress, UploadCallback callback) throws PatchException;

    /**
     * Takes already downloaded {@link PatchData static patch data} and prepares the patch to be installed,
     * examined and/or simulated
     * @param patchData
     * @return
     */
    Patch trackPatch(PatchData patchData) throws PatchException;

    /**
     * <p>We can install many patches at once, but those have to be of the same {@link PatchKind}.</p>
     * <p>This method returns a <em>handle</em> to <em>patch transaction</em> that has to be passed around
     * when installing consecutive patches. This <em>handle</em> is effectively a temporary branch name when patches
     * are applied</p>
     * @param kind
     * @return
     */
    String beginInstallation(PatchKind kind);

    /**
     * <p>When patch installation <em>transaction</em> is started, we can install as many {@link PatchKind#NON_ROLLUP}
     * patches we like or install single {@link PatchKind#ROLLUP} patch</p>
     * <p>This won't affect ${karaf.home} until we {@link #commitInstallation(String) commit the installation}.</p>
     * @param transaction
     * @param patch
     * @param bundleUpdatesInThisPatch
     */
    void install(String transaction, Patch patch, List<BundleUpdate> bundleUpdatesInThisPatch);

    /**
     * <p>After successful patch(es) installation, we commit the transaction = do a fast forward merge
     * of main patch branch to transaction branch</p>
     * @param transaction
     */
    void commitInstallation(String transaction);

    /**
     * <p>When something goes wrong (or during simulation?) we delete transaction branch without affecting
     * main patch branch.</p>
     * @param transaction
     */
    void rollbackInstallation(String transaction);

    /**
     * <p>Rolling back a patch (which is different that rolling back transaction for patches installation) is
     * a matter of restoring to previous point in history</p>
     * @param patchData
     */
    void rollback(PatchData patchData);

    /**
     * Fabric-mode operation - when patching profiles during {@link PatchKind#ROLLUP rollup patch} installation
     * we have to find some common point between a series of user changes (<code>profile-edit</code>s) and changes
     * related to product itself (and installed patches). Profiles from patch
     * {@link #installProfiles(File, String, Patch, ProfileUpdateStrategy) will be installed} in <em>patch branch</em>
     * and then this branch will be merged with <em>version branch</em> that contains user edits.
     * @param gitRepository
     * @param versionId
     * @return temporary branch name that'll contain changes from patch. <code>versionId</code> branch is main
     * version branch
     */
    String findLatestPatchRevision(File gitRepository, String versionId);

    /**
     * Fabric-mode operation - takes external {@link Git} and updates a branch (version) with new profiles
     * shipped with new {@link PatchKind#ROLLUP rollup patch}.
     * @param gitRepository
     * @param versionId
     * @param patch
     * @param strategy
     */
    void installProfiles(File gitRepository, String versionId, Patch patch, ProfileUpdateStrategy strategy);

    /**
     * Fabric-mode operation - After copying changes from {@link PatchKind#ROLLUP R patch} to <em>patch branch</em>,
     * we have to merge user changes in <em>version branch</em> (like <code>1.0</code>) with <em>patch branch</em>.
     * There may be some conflicts, but we have several
     * {@link io.fabric8.patch.management.conflicts.Resolver conflict resolvers} to choose from.
     * @param patch
     * @param gitRepository
     * @param versionBranch
     * @param patchBranch
     */
    void mergeProfileChanges(Patch patch, File gitRepository, String versionBranch, String patchBranch);

    /**
     * Patching services can be called from high level services. This method takes a map of versions
     * (<code>io.fabric8.version</code> PID). It's job is to update static resources of the container.
     * This method should be called in fabric mode.
     * @param versions
     * @param urls list of urls of critical bundles that have to be downloaded to ${karaf.default.repository}
     * @param localMavenRepository
     * @param callback
     * @return <code>true</code> if container needs restart
     */
    boolean alignTo(Map<String, String> versions, List<String> urls, File localMavenRepository, Runnable callback) throws PatchException;

    /**
     * Checks whether we're in <code>admin:create</code> based child container
     * @return
     */
    boolean isStandaloneChild();

    /**
     * Pushes data from <em>patch git repository</em> to <em>fabric git repository</em>
     * @param verbose
     */
    void pushPatchInfo(boolean verbose) throws IOException, GitAPIException;

    /**
     * Logs information about <strong>push</strong> operation
     * @param results
     * @param repository
     */
    void logPushResult(Iterable<PushResult> results, Repository repository, boolean verbose) throws IOException;

    /**
     * Callback to be passed to method that uploads content of retrieved patches to remote repository.
     */
    interface UploadCallback {

        void doWithUrlConnection(URLConnection connection) throws ProtocolException;

    }

}
