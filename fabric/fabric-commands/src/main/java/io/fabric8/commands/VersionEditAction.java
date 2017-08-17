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
package io.fabric8.commands;

import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.karaf.shell.console.AbstractAction;

import io.fabric8.api.FabricService;
import io.fabric8.api.ProfileRegistry;
import io.fabric8.api.ProfileService;
import io.fabric8.api.Version;

@Command(name = VersionEdit.FUNCTION_VALUE, scope = VersionEdit.SCOPE_VALUE, description = VersionEdit.DESCRIPTION)
public class VersionEditAction extends AbstractAction {

    @Option(name = "--description", description = "The description notes of this version.")
    private String description;
    @Argument(index = 0, description = "The version to modify.", required = true)
    private String versionId;

    private final FabricService fabricService;

    VersionEditAction(FabricService fabricService) {
        this.fabricService = fabricService;
    }

    @Override
    protected Object doExecute() throws Exception {

        ProfileService profileService = fabricService.adapt(ProfileService.class);
        ProfileRegistry profileRegistry = fabricService.adapt(ProfileRegistry.class);
        Version version = profileService.getVersion(versionId);
        if(version == null){
            System.err.println("Version " + versionId + " does not exist.");
            return null;
        }
        profileRegistry.modifyVersionDescription(version, description);
        return null;
    }
}
