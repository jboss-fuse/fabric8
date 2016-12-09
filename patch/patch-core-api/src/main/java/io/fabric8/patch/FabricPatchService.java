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
package io.fabric8.patch;

import java.io.IOException;

import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.patch.management.ProfileUpdateStrategy;

/**
 * Fabric patching service used by commands. It is used together with more general purpose {@link Service}.
 * The general service is used to track patches and this service is used for installation in fabric environment.
 */
public interface FabricPatchService {

    /**
     * Installs a patch in fabric environment. Patch is installed in specific <code>versionId</code>.
     * This method should be used for Rollup patches.
     * TODO: ensure internal verification for what method can be used to what kind of patch.
     * @param patch
     * @param simulation
     * @param versionId
     * @param upload
     * @param username
     * @param password
     * @param strategy    @return
     */
    PatchResult install(Patch patch, boolean simulation, String versionId, boolean upload, String username, String password, ProfileUpdateStrategy strategy)
            throws IOException;

    /**
     * Pushes all patch-tracking branches to cluster git server
     * @return URL to which the push was made
     */
    String synchronize(boolean verbose) throws Exception;

}
