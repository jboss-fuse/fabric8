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

/**
 * Command to retrieve patch details from patch-management system
 */
public class PatchDetailsRequest {

    private final String patchId;
    private boolean bundles;
    private boolean files;
    private boolean diff;

    public PatchDetailsRequest(String patchId, boolean bundles, boolean files, boolean diff) {
        this.patchId = patchId;
        this.bundles = bundles;
        this.files = files;
        this.diff = diff;
    }

    public String getPatchId() {
        return patchId;
    }

    public boolean isBundles() {
        return bundles;
    }

    public boolean isFiles() {
        return files;
    }

    public boolean isDiff() {
        return diff;
    }

}
