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

import io.fabric8.patch.FabricPatchService;
import io.fabric8.patch.Service;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.patch.management.ProfileUpdateStrategy;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;

@Command(scope = "patch", name = "fabric-install", description = "Install a patch in fabric environment")
public class FabricInstallAction extends PatchActionSupport {

    @Argument(name = "PATCH", description = "Name of the patch to install", required = true, multiValued = false)
    String patchId;

    @Option(name = "-s", aliases = { "--simulation" }, description = "Simulates installation of the patch")
    boolean simulation = false;

    @Option(name = "--merge-overwrite", description = "Merge strategy: replace entire profile file with a patched one")
    boolean mergeOverwrite = false;

    @Option(name = "--merge-prefer-new", description = "Merge strategy: take new properties, replace existing")
    boolean mergeSelectNew = false;

    @Option(name = "--merge-keep-old", description = "Merge strategy: take new properties, keep existing")
    boolean mergeKeepExisting = false;

    @Option(name = "--upload", description = "Upload artifacts from patch to Maven repository")
    boolean upload = false;

    @Option(name = "-u", aliases = { "--username" }, description = "Remote user name", required = false, multiValued = false)
    private String username;

    @Option(name = "-p", aliases = { "--password" }, description = "Remote user password", required = false, multiValued = false)
    private String password;

    @Option(name = "--version", description = "Version in which the patch should be installed", required = true, multiValued = false)
    private String versionId;

    private final FabricPatchService fabricPatchService;

    FabricInstallAction(Service service, FabricPatchService fabricPatchService) {
        super(service);
        this.fabricPatchService = fabricPatchService;
    }

    @Override
    protected void doExecute(Service service) throws Exception {
        Patch patch = super.getPatch(patchId);
        ProfileUpdateStrategy strategy = ProfileUpdateStrategy.GIT;
        if ((mergeOverwrite && (mergeSelectNew || mergeKeepExisting))
                || (mergeSelectNew && (mergeOverwrite || mergeKeepExisting))
                || (mergeKeepExisting && (mergeOverwrite || mergeSelectNew))) {
            throw new UnsupportedOperationException("Please select single merge strategy");
        }
        if (mergeKeepExisting) {
            strategy = ProfileUpdateStrategy.PROPERTIES_PREFER_EXISTING;
        } else if (mergeSelectNew) {
            strategy = ProfileUpdateStrategy.PROPERTIES_PREFER_NEW;
        }
        PatchResult result = fabricPatchService.install(patch, simulation, versionId, upload, username, password, strategy);
    }

}
