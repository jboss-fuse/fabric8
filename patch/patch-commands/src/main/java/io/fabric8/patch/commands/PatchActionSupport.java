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

import java.util.*;

import io.fabric8.patch.Service;
import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchResult;
import org.apache.karaf.shell.console.AbstractAction;

import static io.fabric8.patch.management.Utils.stripSymbolicName;

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

    protected void display(PatchResult result) {
        int l1 = "[name]".length(), l2 = "[old]".length(), l3 = "[new]".length();
        for (BundleUpdate update : result.getBundleUpdates()) {
            if (stripSymbolicName(update.getSymbolicName()).length() > l1) {
                l1 = stripSymbolicName(update.getSymbolicName()).length();
            }
            if (update.getPreviousVersion().length() > l2) {
                l2 = update.getPreviousVersion().length();
            }
            if (update.getNewVersion().length() > l3) {
                l3 = update.getNewVersion().length();
            }
        }
        System.out.println(String.format("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s", "[name]", "[old]", "[new]"));
        java.util.List<BundleUpdate> updates = new ArrayList<>(result.getBundleUpdates());
        Collections.sort(updates, new Comparator<BundleUpdate>() {
            @Override
            public int compare(BundleUpdate o1, BundleUpdate o2) {
                return o1.getSymbolicName().compareTo(o2.getSymbolicName());
            }
        });
        for (BundleUpdate update : updates) {
            System.out.println(String.format("%-" + l1 + "s | %-" + l2 + "s | %-" + l3 + "s", stripSymbolicName(update.getSymbolicName()), update.getPreviousVersion(), update.getNewVersion()));
        }
    }

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
