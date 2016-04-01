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
package io.fabric8.patch;

import java.net.URL;

import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchResult;

/**
 * High-level patch management service (standalone mode) to be used by commands
 */
public interface Service {

    String PATCH_LOCATION = "fabric8.patch.location";
    String NEW_PATCH_LOCATION = "fuse.patch.location";

    /**
     * List all available patches
     * @return
     */
    Iterable<Patch> getPatches();

    /**
     * Get patch with a specific Id
     * @param id
     * @return
     */
    Patch getPatch(String id);

    /**
     * Retrieves a patch file (or ZIPped set of patches) from a given URL and returns a list of informations about found {@link Patch patches}
     * @param url
     * @return
     */
    Iterable<Patch> download(URL url);

    /**
     * Install already added patch
     * @param patch
     * @param simulate
     * @return
     */
    PatchResult install(Patch patch, boolean simulate);

    /**
     * Install already added patch
     * @param patch
     * @param simulate
     * @param synchronous
     * @return
     */
    PatchResult install(Patch patch, boolean simulate, boolean synchronous);

    /**
     * Rolls back an installed patch
     * @param patch
     * @param simulate
     * @param force
     */
    void rollback(Patch patch, boolean simulate, boolean force);

}
