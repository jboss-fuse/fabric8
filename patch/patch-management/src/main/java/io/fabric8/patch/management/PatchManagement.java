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

import java.net.URL;
import java.util.List;

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
     */
    void install(String transaction, Patch patch);

    /**
     * <p>After successful patch(es) installation, we commit the transaction = do a fast forward merge
     * of <code>master</code> branch to transaction branch</p>
     * @param transaction
     */
    void commitInstallation(String transaction);

    /**
     * <p>When something goes wrong (or during simulation?) we delete transaction branch without affecting
     * <code>master</code> branch.</p>
     * @param transaction
     */
    void rollbackInstallation(String transaction);

    /**
     * <p>Rolling back a patch (which is different that rolling back transaction for patches installation) is
     * a matter of restoring to previous point in history</p>
     * @param result
     */
    void rollback(PatchResult result);

}
