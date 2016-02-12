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
import io.fabric8.patch.management.PatchException;
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
        int l1 = "[name]".length(), l2 = "[installed]".length(), l3 = "[description]".length();
        for (Patch patch : patches) {
            if (patch.getPatchData().getId().length() > l1) {
                l1 = patch.getPatchData().getId().length();
            }
            if (patch.getResult() != null) {
                java.util.List<String> versions = patch.getResult().getVersions();
                if (versions.size() > 0) {
                    // patch installed in fabric
                    for (String v : versions) {
                        if (("Version " + v).length() > l2) {
                            l2 = ("Version " + v).length();
                        }
                    }
                }
                java.util.List<String> karafBases = patch.getResult().getKarafBases();
                if (karafBases.size() > 0) {
                    // patch installed in standalone mode (root, admin:create)
                    for (String kbt : karafBases) {
                        String[] kb = kbt.split("\\s*\\|\\s*");
                        if (kb[0].length() > l2) {
                            l2 = kb[0].length();
                        }
                    }
                }
            }
            String desc = patch.getPatchData().getDescription() != null ? patch.getPatchData().getDescription() : "";
            if (desc.length() > l3) {
                l3 = desc.length();
            }
        }

        System.out.println(String.format("%-" + l1 + "s %-" + l2 + "s %-" + l3 + "s", "[name]", "[installed]", "[description]"));
        for (Patch patch : patches) {
            String desc = patch.getPatchData().getDescription() != null ? patch.getPatchData().getDescription() : "";
            String installed = Boolean.toString(patch.isInstalled());
            boolean fabric = false;
            if (patch.getResult() != null) {
                if (patch.getResult().getVersions().size() > 0) {
                    installed = "Version " + patch.getResult().getVersions().get(0);
                    fabric = true;
                } else if (patch.getResult().getKarafBases().size() > 0) {
                    String kbt = patch.getResult().getKarafBases().get(0);
                    String[] kb = kbt.split("\\s*\\|\\s*");
                    installed = kb[0];
                }
            }
            System.out.println(String.format("%-" + l1 + "s %-" + l2 + "s %-" + l3 + "s", patch.getPatchData().getId(),
                    installed, desc));
            if (fabric && patch.getResult() != null && patch.getResult().getVersions().size() > 1) {
                for (String v : patch.getResult().getVersions().subList(1, patch.getResult().getVersions().size())) {
                    System.out.println(String.format("%-" + l1 + "s %-" + l2 + "s %-" + l3 + "s", " ",
                            "Version " + v, " "));
                }
            }
            if (!fabric && patch.getResult() != null && patch.getResult().getKarafBases().size() > 1) {
                for (String kbt : patch.getResult().getKarafBases().subList(1, patch.getResult().getKarafBases().size())) {
                    String[] kb = kbt.split("\\s*\\|\\s*");
                    System.out.println(String.format("%-" + l1 + "s %-" + l2 + "s %-" + l3 + "s", " ",
                            kb[0], " "));
                }
            }

            if (listBundles) {
                for (String b : patch.getPatchData().getBundles()) {
                    System.out.println(String.format(" - %s", b));
                }
            }
        }
    }

    /**
     * Returns existing (added/tracked) patch
     * @param patchId
     * @return
     */
    protected Patch getPatch(String patchId) {
        Patch patch = service.getPatch(patchId);
        if (patch == null) {
            throw new PatchException("Patch '" + patchId + "' not found");
        }
        if (patch.isInstalled()) {
            throw new PatchException("Patch '" + patchId + "' is already installed");
        }
        return patch;
    }

}
