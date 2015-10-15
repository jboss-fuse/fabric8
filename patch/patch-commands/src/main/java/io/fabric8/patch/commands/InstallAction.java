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
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchException;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.utils.shell.ShellUtils;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;

@Command(scope = "patch", name = "install", description = "Install a patch")
public class InstallAction extends PatchActionSupport {

    @Argument(name = "PATCH", description = "name of the patch to install", required = true, multiValued = false)
    String patchId;

    @Option(name = "--simulation", description = "Simulates installation of the patch")
    boolean simulation = false;

    @Option(name = "--synchronous", description = "Synchronous installation (use with caution)")
    boolean synchronous;

    InstallAction(Service service) {
        super(service);
    }

    @Override
    protected void doExecute(Service service) throws Exception {
        Patch patch = super.getPatch(patchId);

        if (patch.getPatchData().getMigratorBundle() != null) {
            System.out.println("This patch cannot be rolled back.  Are you sure you want to install?");
            while (true) {
                String response = ShellUtils.readLine(session, "[y/n]: ", false);
                if (response == null) {
                    return;
                }
                response = response.trim().toLowerCase();
                if (response.equals("y") || response.equals("yes")) {
                    break;
                }
                if (response.equals("n") || response.equals("no")) {
                    return;
                }
            }
        }

        PatchResult result = service.install(patch, simulation, synchronous);
    }

}
