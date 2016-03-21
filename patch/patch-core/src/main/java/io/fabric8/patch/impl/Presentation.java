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
package io.fabric8.patch.impl;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.FeatureUpdate;

import static io.fabric8.patch.management.Utils.stripSymbolicName;

/**
 * Old school code that displays various data related to patch installation/rollback.
 */
public abstract class Presentation {

    private Presentation() {
    }

    /**
     * Displays a table with installed (<code>install == true</code>) or rolledback bundles
     * @param updatesForBundleKeys
     * @param install is this displayed during patch install (<code>true</code>) or rollback (<code>false</code>)
     */
    public static void displayBundleUpdates(Collection<BundleUpdate> updatesForBundleKeys, boolean install) {
        int l1 = "[symbolic name]".length();
        int l2 = "[version]".length();
        int l3 = install ? "[new location]".length() : "[previous location]".length();
        int tu = 0; // updates
        int tuf = 0; // updates as part of features
        int tk = 0; // reinstalls
        int tkf = 0; // reinstalls as part of features
        Map<String, BundleUpdate> map = new TreeMap<>();
        for (BundleUpdate be : updatesForBundleKeys) {
            String sn = be.getSymbolicName() == null ? "" : stripSymbolicName(be.getSymbolicName());
            if (sn.length() > l1) {
                l1 = sn.length();
            }
            if (install) {
                String version = be.getPreviousVersion() == null ? "<update>" : be.getPreviousVersion();
                if (version.length() > l2) {
                    l2 = version.length();
                }
            } else {
                String version = be.getNewVersion() == null ? be.getPreviousVersion() : be.getNewVersion();
                if (version.length() > l2) {
                    l2 = version.length();
                }
            }
            if (install) {
                String newLocation = be.getNewLocation() == null ? "<reinstall>" : be.getNewLocation();
                if (newLocation.length() > l3) {
                    l3 = newLocation.length();
                }
            } else {
                String previousLocation = be.getPreviousLocation();
                if (previousLocation.length() > l3) {
                    l3 = previousLocation.length();
                }
            }

            if (be.getNewLocation() != null) {
                if (be.isIndependent()) {
                    tu++;
                } else {
                    tuf++;
                }
            } else {
                if (be.isIndependent()) {
                    tk++;
                } else {
                    tkf++;
                }
            }
            map.put(be.getSymbolicName(), be);
        }
        if (tu > 0) {
            System.out.printf("========== Bundles to %s (%d):%n", install ? "update" : "downgrade", tu);
            System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                    "[symbolic name]", "[version]", install ? "[new location]" : "[previous location]");
            for (Map.Entry<String, BundleUpdate> e : map.entrySet()) {
                BundleUpdate be = e.getValue();
                if (be.isIndependent() && be.getNewLocation() != null) {
                    System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                            be.getSymbolicName() == null ? "" : stripSymbolicName(be.getSymbolicName()),
                            install ? (be.getPreviousVersion() == null ? "<update>" : be.getPreviousVersion()) : be.getNewVersion(),
                            install ? be.getNewLocation() : be.getPreviousLocation());
                }
            }
        }
        if (tuf > 0) {
            System.out.printf("========== Bundles to %s as part of features or core bundles (%d):%n",
                    install ? "update" : "downgrade", tuf);
            System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                    "[symbolic name]", "[version]", install ? "[new location]" : "[previous location]");
            for (Map.Entry<String, BundleUpdate> e : map.entrySet()) {
                BundleUpdate be = e.getValue();
                if (!be.isIndependent() && be.getNewLocation() != null) {
                    System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                            be.getSymbolicName() == null ? "" : stripSymbolicName(be.getSymbolicName()),
                            install ? be.getPreviousVersion() : be.getNewVersion(),
                            install ? be.getNewLocation() : be.getPreviousLocation());
                }
            }
        }
        if (tk > 0) {
            System.out.printf("========== Bundles to reinstall (%d):%n", tk);
            System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                    "[symbolic name]", "[version]", "[location]");
            for (Map.Entry<String, BundleUpdate> e : map.entrySet()) {
                BundleUpdate be = e.getValue();
                if (be.isIndependent() && be.getNewLocation() == null) {
                    System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                            be.getSymbolicName() == null ? "" : stripSymbolicName(be.getSymbolicName()),
                            be.getPreviousVersion(),
                            be.getPreviousLocation());
                }
            }
        }
        if (tkf > 0) {
            System.out.printf("========== Bundles to reinstall as part of features or core bundles (%d):%n", tkf);
            System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                    "[symbolic name]", "[version]", "[location]");
            for (Map.Entry<String, BundleUpdate> e : map.entrySet()) {
                BundleUpdate be = e.getValue();
                if (!be.isIndependent() && be.getNewLocation() == null) {
                    System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                            be.getSymbolicName() == null ? "" : stripSymbolicName(be.getSymbolicName()),
                            be.getPreviousVersion(),
                            be.getPreviousLocation());
                }
            }
        }

        System.out.flush();
    }

    /**
     * Displays a table with installed (<code>install == true</code>) or rolledback features
     * @param featureUpdates
     * @param install is this displayed during patch install (<code>true</code>) or rollback (<code>false</code>)
     */
    public static void displayFeatureUpdates(Collection<FeatureUpdate> featureUpdates, boolean install) {
        Set<String> toKeep = new TreeSet<>();
        Set<String> toRemove = new TreeSet<>();
        Set<String> toAdd = new TreeSet<>();
        for (FeatureUpdate fu : featureUpdates) {
            if (install) {
                toRemove.add(fu.getPreviousRepository());
            } else if (fu.getNewRepository() != null) {
                toRemove.add(fu.getNewRepository());
            }
            if (fu.getNewRepository() != null) {
                toAdd.add(install ? fu.getNewRepository() : fu.getPreviousRepository());
            } else if (install) {
                // keep old only during installation, when rolling back rollup patch, we don't want to keep
                // any new repository
                toKeep.add(fu.getPreviousRepository());
            }
        }
        toRemove.removeAll(toKeep);
        System.out.printf("========== Repositories to remove (%d):%n", toRemove.size());
        for (String repo : toRemove) {
            System.out.println(" - " + repo);
        }
        System.out.printf("========== Repositories to add (%d):%n", toAdd.size());
        for (String repo : toAdd) {
            System.out.println(" - " + repo);
        }
        System.out.printf("========== Repositories to keep (%d):%n", toKeep.size());
        for (String repo : toKeep) {
            System.out.println(" - " + repo);
        }

        System.out.printf("========== Features to (%s):%n", install ? "update" : "downgrade");
        int l1 = "[name]".length();
        int l2 = "[version]".length();
        int l3 = install ? "[new version]".length() : "[previous version]".length();
        Map<String, FeatureUpdate> map = new TreeMap<>();
        for (FeatureUpdate fu : featureUpdates) {
            if (fu.getName() == null) {
                continue;
            }
            if (fu.getName().length() > l1) {
                l1 = fu.getName().length();
            }
            if (install) {
                if (fu.getPreviousVersion().length() > l2) {
                    l2 = fu.getPreviousVersion().length();
                }
                if (fu.getNewVersion() != null) {
                    if (fu.getNewVersion().length() > l3) {
                        l3 = fu.getNewVersion().length();
                    }
                }
            } else {
                if (fu.getNewVersion() != null) {
                    if (fu.getNewVersion().length() > l2) {
                        l2 = fu.getNewVersion().length();
                    }
                }
                if (fu.getPreviousVersion().length() > l3) {
                    l3 = fu.getPreviousVersion().length();
                }
            }
            map.put(fu.getName(), fu);
        }
        System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                "[name]", "[version]", install ? "[new version]" : "[previous version]");
        for (FeatureUpdate fu : map.values()) {
            System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                    fu.getName(),
                    install ? fu.getPreviousVersion() : fu.getNewVersion() == null ? "<reinstall>" : fu.getNewVersion(),
                    install ? fu.getNewVersion() == null ? "<reinstall>" : fu.getNewVersion() : fu.getPreviousVersion()
            );
        }

        System.out.flush();
    }

}
