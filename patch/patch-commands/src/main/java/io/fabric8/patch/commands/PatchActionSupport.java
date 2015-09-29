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
import org.apache.karaf.shell.console.AbstractAction;

public abstract class PatchActionSupport extends AbstractAction {

    protected Service service;

    protected PatchActionSupport(Service service) {
        this.service = service;
    }

    @Override
    protected Object doExecute() throws Exception {
        doExecute(service);
        return null;
    }

    protected abstract void doExecute(Service service) throws Exception;

//    protected void display(Result result) {
//        System.out.println(String.format("%-40s %-10s %-10s", "[name]", "[old]", "[new]"));
//        for (BundleUpdate update : result.getUpdates()) {
//            System.out.println(String.format("%-40s %-10s %-10s", update.getSymbolicName(), update.getPreviousVersion(), update.getNewVersion()));
//        }
//    }

    /**
     * Displays a list of {@link Patch patches} in short format. Each {@link Patch#getManagedPatch()} is already
     * tracked.
     * @param patches
     * @param listBundles
     */
    protected void display(Iterable<Patch> patches, boolean listBundles) {
        System.out.println(String.format("%-40s %-11s %s", "[name]", "[installed]", "[description]"));
        for (Patch patch : patches) {
            String desc = patch.getPatchData().getDescription() != null ? patch.getPatchData().getDescription() : "";
            System.out.println(String.format("%-40s %-11s %s", patch.getPatchData().getId(), Boolean.toString(patch.isInstalled()), desc));
            if (listBundles) {
                for (String b : patch.getPatchData().getBundles()) {
                    System.out.println(String.format("\t%s", b));
                }
            }
        }
    }

}
