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
package io.fabric8.patch.commands;

import io.fabric8.patch.Service;
import io.fabric8.patch.management.ManagedPatch;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchDetailsRequest;
import io.fabric8.patch.management.PatchException;
import io.fabric8.patch.management.PatchManagement;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;

@Command(scope = "patch", name = "show", description = "Display information about added/installed patch")
public class ShowAction extends PatchActionSupport {

    private final PatchManagement patchManagement;

    @Argument(name = "PATCH", description = "name of the patch to display", required = true, multiValued = false)
    String patchId;

    @Option(name = "--bundles", description = "Display the list of bundles for the patch")
    boolean bundles;

    @Option(name = "--files", description = "Display list of files added/modified/removed in a patch (without the files in ${karaf.home}/system)")
    boolean files;

    @Option(name = "--diff", description = "Display unified diff of files modified in a patch (without the files in ${karaf.home}/system)")
    boolean diff;

    ShowAction(Service service, PatchManagement patchManagement) {
        super(service);
        this.patchManagement = patchManagement;
    }

    @Override
    protected void doExecute(Service service) throws Exception {
        Patch patch = patchManagement.loadPatch(new PatchDetailsRequest(patchId, bundles, files, diff));
        
        if (patch == null) {
            throw new PatchException("Patch '" + patchId + "' not found");
        }
        System.out.println(String.format("Patch ID: %s", patch.getPatchData().getId()));
        if (patch.getManagedPatch() != null) {
            System.out.println(String.format("Patch Commit ID: %s", patch.getManagedPatch().getCommitId()));
        }
        if (bundles) {
            System.out.println(String.format("#### %d Bundles%s", patch.getPatchData().getBundles().size(), patch.getPatchData().getBundles().size() == 0 ? "" : ":"));
            iterate(patch.getPatchData().getBundles());
        }
        if (files) {
            ManagedPatch details = patch.getManagedPatch();
            System.out.println(String.format("#### %d Files added%s", details.getFilesAdded().size(), details.getFilesAdded().size() == 0 ? "" : ":"));
            iterate(details.getFilesAdded());
            System.out.println(String.format("#### %d Files modified%s", details.getFilesModified().size(), details.getFilesModified().size() == 0 ? "" : ":"));
            iterate(details.getFilesModified());
            System.out.println(String.format("#### %d Files removed%s", details.getFilesRemoved().size(), details.getFilesRemoved().size() == 0 ? "" : ":"));
            iterate(details.getFilesRemoved());
        }
        if (diff) {
            System.out.println("#### Patch changes:\n" + patch.getManagedPatch().getUnifiedDiff());
        }
    }

    /**
     * List file names
     * @param fileNames
     */
    private void iterate(java.util.List<String> fileNames) {
        for (String name : fileNames) {
            System.out.println(String.format(" - %s", name));
        }
    }

}
