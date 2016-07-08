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
 * A patch that can be installed, rolled back, simulated or examined.
 */
public class Patch {

    // static patch information - content of patch file
    private PatchData patchData;
    // dynamic patch information - patch tracked by patch management, not necessarly installed
    private ManagedPatch managedPatch;
    // information about installed patch
    private PatchResult result;

    public Patch() {
    }

    public Patch(PatchData patchData, ManagedPatch mp) {
        this.patchData = patchData;
        this.managedPatch = mp;
    }

    public boolean isInstalled() {
        boolean inThisContainer = result != null && result.getVersions().size() == 0;
        if (inThisContainer) {
            // ENTESB-5682: let's handle *.patch.result created by earlier versions of patch-management, where
            // we didn't store list of bases in standalone mode. Simply existence of *.patch.result file meant
            // "patch is installed"
            boolean installed = result.getKarafBases().size() == 0;
            for (String kbt : result.getKarafBases()) {
                installed |= kbt.startsWith(System.getProperty("karaf.name"));
            }
            inThisContainer = installed;
        }
        return result != null && inThisContainer;
    }

    public PatchData getPatchData() {
        return patchData;
    }

    public void setPatchData(PatchData patchData) {
        this.patchData = patchData;
    }

    public ManagedPatch getManagedPatch() {
        return managedPatch;
    }

    public void setManagedPatch(ManagedPatch managedPatch) {
        this.managedPatch = managedPatch;
    }

    public PatchResult getResult() {
        return result;
    }

    public void setResult(PatchResult result) {
        this.result = result;
    }

}
