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
import org.apache.felix.gogo.commands.Command;
import org.apache.karaf.shell.console.AbstractAction;

@Command(scope = "patch", name = "fabric-synchronize", description = "Synchronize information about patches to cluster's git server")
public class FabricSynchronizeAction extends AbstractAction {

    private final FabricPatchService fabricPatchService;

    FabricSynchronizeAction(FabricPatchService fabricPatchService) {
        this.fabricPatchService = fabricPatchService;
    }

    @Override
    protected Object doExecute() throws Exception {
        String remoteUrl = fabricPatchService.synchronize(true);
        if (remoteUrl != null) {
            System.out.println("Patch information synchronized to git repository at: " + remoteUrl);
        }

        return null;
    }

}
