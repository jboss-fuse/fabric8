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

import java.io.FileNotFoundException;
import java.io.IOException;
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

}
