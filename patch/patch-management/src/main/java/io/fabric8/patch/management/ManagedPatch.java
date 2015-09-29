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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Information about already downloaded patch stored and tracked by patch management system - dynamic patch
 * information.
 */
public class ManagedPatch {

    /** ID of a patch (from the ZIP/descriptor.patch) */
    private String patchId;
    /** SHA1 commit id inside git repository that points to a patch */
    private String commitId;

    private List<String> filesAdded = new ArrayList<>();
    private List<String> filesModified = new ArrayList<>();
    private List<String> filesRemoved = new ArrayList<>();

    private String unifiedDiff;

    // TODO: ↓
    private Map<String, String> modificationDiffs = new HashMap<>();

    public String getCommitId() {
        return commitId;
    }

    public void setCommitId(String commitId) {
        this.commitId = commitId;
    }

    public String getPatchId() {
        return patchId;
    }

    public void setPatchId(String patchId) {
        this.patchId = patchId;
    }

    public String getUnifiedDiff() {
        return unifiedDiff;
    }

    public void setUnifiedDiff(String unifiedDiff) {
        this.unifiedDiff = unifiedDiff;
    }

    public List<String> getFilesAdded() {
        return filesAdded;
    }

    public List<String> getFilesModified() {
        return filesModified;
    }

    public List<String> getFilesRemoved() {
        return filesRemoved;
    }

}
