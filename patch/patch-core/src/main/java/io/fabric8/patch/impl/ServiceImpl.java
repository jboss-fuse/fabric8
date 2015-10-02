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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import io.fabric8.patch.Service;
import io.fabric8.patch.management.Artifact;
import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.FeatureUpdate;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchData;
import io.fabric8.patch.management.PatchDetailsRequest;
import io.fabric8.patch.management.PatchException;
import io.fabric8.patch.management.PatchKind;
import io.fabric8.patch.management.PatchManagement;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.patch.management.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.utils.manifest.Clause;
import org.apache.felix.utils.manifest.Parser;
import org.apache.felix.utils.version.VersionRange;
import org.apache.felix.utils.version.VersionTable;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.Repository;
import org.apache.karaf.util.bundles.BundleUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.component.ComponentContext;
import org.osgi.util.tracker.ServiceTracker;

import static io.fabric8.patch.management.Utils.mvnurlToArtifact;
import static io.fabric8.patch.management.Utils.stripSymbolicName;

@Component(immediate = true, metatype = false)
@org.apache.felix.scr.annotations.Service(Service.class)
public class ServiceImpl implements Service {

    private static final String ID = "id";
    private static final String DESCRIPTION = "description";
    private static final String DATE = "date";
    private static final String BUNDLES = "bundle";
    private static final String UPDATES = "update";
    private static final String COUNT = "count";
    private static final String RANGE = "range";
    private static final String SYMBOLIC_NAME = "symbolic-name";
    private static final String NEW_VERSION = "new-version";
    private static final String NEW_LOCATION = "new-location";
    private static final String OLD_VERSION = "old-version";
    private static final String OLD_LOCATION = "old-location";
    private static final String STARTUP = "startup";
    private static final String OVERRIDES = "overrides";

    private BundleContext bundleContext;
    private File patchDir;

    @Reference(referenceInterface = PatchManagement.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.STATIC)
    private PatchManagement patchManagement;

    @Reference(referenceInterface = FeaturesService.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.STATIC)
    private FeaturesService featuresService;

    private File karafHome;
    // by default it's ${karaf.home}/system
    private File repository;

    @Activate
    void activate(ComponentContext componentContext) {
        // Use system bundle' bundle context to avoid running into
        // "Invalid BundleContext" exceptions when updating bundles
        this.bundleContext = componentContext.getBundleContext().getBundle(0).getBundleContext();
        String dir = this.bundleContext.getProperty(NEW_PATCH_LOCATION);
        if (dir != null) {
            patchDir = new File(dir);
        } else {
            dir = this.bundleContext.getProperty(PATCH_LOCATION);
            if (dir != null) {
                patchDir = new File(dir);
            } else {
                // only now fallback to datafile of system bundle
                patchDir = this.bundleContext.getDataFile("patches");
            }
        }
        if (!patchDir.isDirectory()) {
            patchDir.mkdirs();
            if (!patchDir.isDirectory()) {
                throw new PatchException("Unable to create patch folder");
            }
        }

        this.karafHome = new File(bundleContext.getProperty("karaf.home"));
        this.repository = new File(bundleContext.getProperty("karaf.default.repository"));

        load(true);
    }

    @Override
    public Iterable<Patch> getPatches() {
        return Collections.unmodifiableCollection(load(true).values());
    }

    @Override
    public Patch getPatch(String id) {
        return patchManagement.loadPatch(new PatchDetailsRequest(id));
    }

    @Override
    public Iterable<Patch> download(URL url) {
        try {
            List<PatchData> patchesData = patchManagement.fetchPatches(url);
            List<Patch> patches = new ArrayList<>(patchesData.size());
            for (PatchData patchData : patchesData) {
                Patch patch = patchManagement.trackPatch(patchData);
                patches.add(patch);
            }
            return patches;
        } catch (Exception e) {
            throw new PatchException("Unable to download patch from url " + url, e);
        }
    }

    /**
     * Loads available patches without caching
     * @param details whether to load {@link io.fabric8.patch.management.ManagedPatch} details too
     * @return
     */
    private Map<String, Patch> load(boolean details) {
        List<Patch> patchesList = patchManagement.listPatches(details);
        Map<String, Patch> patches = new HashMap<String, Patch>();
        for (Patch patch : patchesList) {
            patches.put(patch.getPatchData().getId(), patch);
        }
        return patches;
    }

    /**
     * Used by the patch client when executing the script in the console
     * @param ids
     */
    public void cliInstall(String[] ids) {
        final List<Patch> patches = new ArrayList<Patch>();
        for (String id : ids) {
            Patch patch = getPatch(id);
            if (patch == null) {
                throw new IllegalArgumentException("Unknown patch: " + id);
            }
            patches.add(patch);
        }
        install(patches, false, false);
    }

    @Override
    public PatchResult install(Patch patch, boolean simulate) {
        return install(patch, simulate, true);
    }

    @Override
    public PatchResult install(Patch patch, boolean simulate, boolean synchronous) {
        Map<String, PatchResult> results = install(Collections.singleton(patch), simulate, synchronous);
        return results.get(patch.getPatchData().getId());
    }

    /**
     * <p>Main installation method. Installing a patch in non-fabric mode is a matter of correct merge (cherry-pick, merge,
     * rebase) of patch branch into <code>master</code> branch.</p>
     * <p>Instaling a patch has three goals:<ul>
     *     <li>update static files/libs in KARAF_HOME</li>
     *     <li>update (uninstall+install) features</li>
     *     <li>update remaining bundles (not yet updated during feature update)</li>
     * </ul></p>
     * @param patches
     * @param simulate
     * @param synchronous
     * @return
     */
    private Map<String, PatchResult> install(final Collection<Patch> patches, final boolean simulate, boolean synchronous) {
        PatchKind kind = checkConsistency(patches);
        checkPrerequisites(patches);
        String transaction = null;

        // poor-man's lifecycle management in case when SCR nullifies our reference
        PatchManagement pm = patchManagement;
        FeaturesService fs = featuresService;

        try {
            // Compute individual patch results (patchId -> Result)
            final Map<String, PatchResult> results = new LinkedHashMap<String, PatchResult>();

            // current state of the framework
            Bundle[] allBundles = bundleContext.getBundles();
            Map<String, Repository> allFeatureRepositories = getAvailableFeatureRepositories();

            // bundle -> url to update the bundle from
            final Map<Bundle, String> bundleUpdateLocations = new HashMap<>();
            // [feature name|updateable-version] -> url of repository to get it from
            final Map<String, String> featureUpdateRepositories = new HashMap<>();
            // feature name -> url of repository to get it from - in case we have only one feature with that name
            // like "transaction" in karaf-enterprise features
            final Map<String, String> singleFeatureUpdateRepositories = new HashMap<>();

            /* A "key" is name + "update'able version". Such version is current version with micro version == 0 */

            // [symbolic name|updateable-version] -> newest update for the bundle out of all installed patches
            Map<String, BundleUpdate> updatesForBundleKeys = new HashMap<>();
            // [feature name|updateable-version] -> newest update for the feature out of all installed patches
            final Map<String, FeatureUpdate> updatesForFeatureKeys = new HashMap<>();

            // symbolic name -> version -> location
            final BundleVersionHistory history = createBundleVersionHistory();

            // beginning installation transaction = creating of temporary branch in git
            transaction = this.patchManagement.beginInstallation(kind);

            // bundles from etc/startup.properties + felix.framework = all bundles not managed by features
            // these bundles will be treated in special way
            // symbolic name -> Bundle
            Map<String, Bundle> coreBundles = getCoreBundles(allBundles);

            // collect runtime information from patches (features, bundles) and static information (files)
            // runtime info is prepared to apply runtime changes and static info is prepared to update KARAF_HOME files
            for (Patch patch : patches) {
                // list of bundle updates for the current patch - only for the purpose of storing result
                List<BundleUpdate> bundleUpdatesInThisPatch = bundleUpdatesInPatch(patch, allBundles,
                        bundleUpdateLocations, history, updatesForBundleKeys, kind);
                // list of feature updates for the current patch - only for the purpose of storing result
                List<FeatureUpdate> featureUpdatesInThisPatch = featureUpdatesInPatch(patch, allFeatureRepositories,
                        featureUpdateRepositories, singleFeatureUpdateRepositories, updatesForFeatureKeys, kind);

                // each patch may change files, we're not updating the main files yet - it'll be done when
                // install transaction is committed
                patchManagement.install(transaction, patch);

                // each patch may ship a migrator
                installMigratorBundle(patch);

                // prepare patch result before doing runtime changes
                PatchResult result = new PatchResult(patch.getPatchData(), simulate, System.currentTimeMillis(),
                        bundleUpdatesInThisPatch, featureUpdatesInThisPatch);
                results.put(patch.getPatchData().getId(), result);
            }

            // We don't have to update bundles that are uninstalled anyway when uninstalling features we
            // are updating. Updating a feature = uninstall + install
            // When feature is uninstalled, its bundles may get uninstalled too, if they are not referenced
            // from any other feature, including special (we're implementation aware!) "startup" feature
            // that is created during initailization of FeaturesService. As expected, this feature contains
            // all bundles started by other means which is:
            // - felix.framework (system bundle)
            // - all bundles referenced in etc/startup.properties

            // Some special cases
            for (Map.Entry<Bundle, String> entry : bundleUpdateLocations.entrySet()) {
                Bundle bundle = entry.getKey();
                if ("org.ops4j.pax.url.mvn".equals(stripSymbolicName(bundle.getSymbolicName()))) {
                    // handle this bundle specially - update it here
                    Artifact artifact = Utils.mvnurlToArtifact(entry.getValue(), true);
                    URL location = new File(repository,
                            String.format("org/ops4j/pax/url/pax-url-aether/%s/pax-url-aether-%s.jar",
                                    artifact.getVersion(), artifact.getVersion())).toURI().toURL();
                    System.out.printf("Special update of bundle \"%s\" from \"%s\"%n",
                            bundle.getSymbolicName(), location);
                    if (!simulate) {
                        BundleUtils.update(bundle, location);
                        bundle.start();
                    }
                    // replace location - to be stored in result
                    bundleUpdateLocations.put(bundle, location.toString());
                }
            }

            displayFeatureUpdates(updatesForFeatureKeys, simulate);

            // effectively, we will update all the bundles from this list - even if some bundles will be "updated"
            // as part of feature installation
            displayBundleUpdates(bundleUpdateLocations, simulate);

            Runnable task = null;
            if (!simulate) {
                task = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            applyChanges(bundleUpdateLocations);

                            // install new features

                            // Now that we don't have any of the old feature repos installed
                            // Lets re-install the features that were previously installed.
                            try {
                                ServiceTracker<FeaturesService, FeaturesService> tracker = new ServiceTracker<>(bundleContext, FeaturesService.class, null);
                                tracker.open();
                                Object service = tracker.waitForService(30000);
                                if (service != null) {
                                    Method m = service.getClass().getDeclaredMethod("installFeature", String.class, String.class );
                                    if (m != null) {
                                        for (FeatureUpdate update : updatesForFeatureKeys.values()) {
                                            if (simulate) {
                                                System.out.println("Simulation: Enable feature: " + update.getName() + "/" + update.getNewVersion());
                                            } else {
                                                System.out.println("Enable feature: " + update.getName() + "/" + update.getNewVersion());
                                                m.invoke(service, update.getName(), update.getNewVersion());
                                            }
                                        }
                                    }
                                } else {
                                    System.err.println("Can't get OSGi reference to FeaturesService");
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                                System.err.flush();
                            }

                            // persist results of all installed patches
                            for (Patch patch : patches) {
                                PatchResult result = results.get(patch.getPatchData().getId());
                                patch.setResult(result);
                                result.store();
                            }
                        } catch (Exception e) {
                            e.printStackTrace(System.err);
                            System.err.flush();
                        }
                    }
                };
            }

            // uninstall old features and repositories repositories
            if (!simulate) {
                Set<String> oldRepositories = new HashSet<>();
                Set<String> newRepositories = new HashSet<>();
                for (FeatureUpdate fu : updatesForFeatureKeys.values()) {
                    oldRepositories.add(fu.getPreviousRepository());
                    newRepositories.add(fu.getNewRepository());
                }
                Map<String, Repository> repos = new HashMap<>();
                for (Repository r : fs.listRepositories()) {
                    repos.put(r.getURI().toString(), r);
                }
                for (String uri : oldRepositories) {
                    if (repos.containsKey(uri)) {
                        Repository r = repos.get(uri);
                        for (Feature f : r.getFeatures()) {
                            try {
                                if (fs.isInstalled(f)) {
                                    fs.uninstallFeature(f.getName(), f.getVersion(), EnumSet.of(FeaturesService.Option.NoAutoRefreshBundles));
                                }
                            } catch (Exception e) {
                                System.err.println(e.getMessage());
                            }
                        }
                    }
                }
                for (String uri : oldRepositories) {
                    fs.removeRepository(URI.create(uri));
                }
                // TODO: we should add not only repositories related to updated features, but also all from
                // "featureRepositories" property from new etc/org.apache.karaf.features.cfg
                for (Patch p : patches) {
                    for (String uri : p.getPatchData().getFeatureFiles()) {
                        fs.addRepository(URI.create(uri));
                    }
                }
            }

//            Bundle fileinstal = null;
//            Bundle configadmin = null;
//            if (!simulate) {
//                // let's stop fileinstall and configadmin
//                for (Bundle b : allBundles) {
//                    if ("org.apache.felix.fileinstall".equals(b.getSymbolicName())) {
//                        fileinstal = b;
//                    } else if ("org.apache.felix.configadmin".equals(b.getSymbolicName())) {
//                        configadmin = b;
//                    }
//                }
//                if (fileinstal != null) {
//                    fileinstal.stop(Bundle.STOP_TRANSIENT);
//                }
//                if (configadmin != null) {
//                    configadmin.stop(Bundle.STOP_TRANSIENT);
//                }
//            }

            // update bundles (special case: pax-url-aether) and install features
            if (!simulate) {
                if (synchronous) {
                    task.run();
                } else {
                    new Thread(task).start();
                }
            }

//            if (!simulate) {
//                if (configadmin != null) {
//                    configadmin.start(Bundle.START_TRANSIENT);
//                }
//                if (fileinstal != null) {
//                    fileinstal.start(Bundle.START_TRANSIENT);
//                }
//            }

            // update KARAF_HOME

            if (!simulate) {
                pm.commitInstallation(transaction);
            } else {
                patchManagement.rollbackInstallation(transaction);
            }

            return results;
        } catch (Exception e) {
            if (transaction != null) {
                pm.rollbackInstallation(transaction);
            }
            throw new PatchException(e.getMessage(), e);
        }
    }

    private void displayFeatureUpdates(Map<String, FeatureUpdate> featureUpdates, boolean simulate) {
        Set<String> toRemove = new TreeSet<>();
        Set<String> toAdd = new TreeSet<>();
        for (FeatureUpdate fu : featureUpdates.values()) {
            toRemove.add(fu.getPreviousRepository());
            toAdd.add(fu.getNewRepository());
        }
        System.out.println("Repositories to remove:");
        for (String repo : toRemove) {
            System.out.println(" - " + repo);
        }
        System.out.println("Repositories to add:");
        for (String repo : toAdd) {
            System.out.println(" - " + repo);
        }

        System.out.println("Features to update:");
        int l1 = "[name]".length();
        int l2 = "[version]".length();
        int l3 = "[new version]".length();
        for (FeatureUpdate fu : featureUpdates.values()) {
            if (fu.getName().length() > l1) {
                l1 = fu.getName().length();
            }
            if (fu.getPreviousVersion().length() > l2) {
                l2 = fu.getPreviousVersion().length();
            }
            if (fu.getNewVersion().length() > l3) {
                l3 = fu.getNewVersion().length();
            }
        }
        System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                "[name]", "[version]", "[new version]");
        for (FeatureUpdate fu : featureUpdates.values()) {
            System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                    fu.getName(), fu.getPreviousVersion(), fu.getNewVersion());
        }

        if (simulate) {
            System.out.println("Running simulation only - no features are being updated at this time");
        } else {
            System.out.println("Installation of features will begin.");
        }
        System.out.flush();
    }

    private void displayBundleUpdates(Map<Bundle, String> bundleUpdateLocations, boolean simulate) {
        System.out.println("Bundles to update:");
        int l1 = "[symbolic name]".length();
        int l2 = "[version]".length();
        int l3 = "[new location]".length();
        for (Map.Entry<Bundle, String> e : bundleUpdateLocations.entrySet()) {
            String sn = stripSymbolicName(e.getKey().getSymbolicName());
            if (sn.length() > l1) {
                l1 = sn.length();
            }
            String version = e.getKey().getVersion().toString();
            if (version.length() > l2) {
                l2 = version.length();
            }
            String newLocation = e.getValue();
            if (newLocation.length() > l3) {
                l3 = newLocation.length();
            }
        }
        System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                "[symbolic name]", "[version]", "[new location]");
        for (Map.Entry<Bundle, String> e : bundleUpdateLocations.entrySet()) {
            System.out.printf("%-" + l1 + "s   %-" + l2 + "s   %-" + l3 + "s%n",
                    stripSymbolicName(e.getKey().getSymbolicName()),
                    e.getKey().getVersion().toString(), e.getValue());
        }

        if (simulate) {
            System.out.println("Running simulation only - no bundles are being updated at this time");
        } else {
            System.out.println("Installation will begin. The connection may be lost or the console restarted.");
        }
        System.out.flush();
    }

    /**
     * Returns a list of {@link BundleUpdate} for single patch, taking into account already discovered updates
     * @param patch
     * @param allBundles
     * @param bundleUpdateLocations out parameter that gathers update locations for bundles across patches
     * @param history
     * @param updatesForBundleKeys
     * @param kind
     * @return
     * @throws IOException
     */
    private List<BundleUpdate> bundleUpdatesInPatch(Patch patch,
                                                    Bundle[] allBundles,
                                                    Map<Bundle, String> bundleUpdateLocations,
                                                    BundleVersionHistory history,
                                                    Map<String, BundleUpdate> updatesForBundleKeys,
                                                    PatchKind kind) throws IOException {
        List<BundleUpdate> updatesInThisPatch = new LinkedList<>();

        for (String newLocation : patch.getPatchData().getBundles()) {
            // [symbolicName, version] of the new bundle
            String[] symbolicNameVersion = getBundleIdentity(newLocation);
            if (symbolicNameVersion == null) {
                continue;
            }
            String sn = symbolicNameVersion[0];
            String vr = symbolicNameVersion[1];
            Version newVersion = VersionTable.getVersion(vr);

            // if existing bundle is withing this range, update is possible
            VersionRange range = getUpdateableRange(patch, newLocation, newVersion);

            if (range != null) {
                for (Bundle bundle : allBundles) {
                    if (bundle.getBundleId() == 0L) {
                        continue;
                    }
                    if (!stripSymbolicName(sn).equals(stripSymbolicName(bundle.getSymbolicName()))) {
                        continue;
                    }
                    Version oldVersion = bundle.getVersion();
                    if (range.contains(oldVersion)) {
                        String oldLocation = history.getLocation(bundle);
                        BundleUpdate update = new BundleUpdate(sn, newVersion.toString(), newLocation,
                                oldVersion.toString(), oldLocation);
                        updatesInThisPatch.add(update);
                        // Merge result
                        String key = String.format("%s|%s", sn, range.getFloor());
                        BundleUpdate oldUpdate = updatesForBundleKeys.get(key);
                        if (oldUpdate != null) {
                            Version upv = VersionTable.getVersion(oldUpdate.getNewVersion());
                            if (upv.compareTo(newVersion) < 0) {
                                // other patch contains newer update for a bundle
                                updatesForBundleKeys.put(key, update);
                                bundleUpdateLocations.put(bundle, newLocation);
                            }
                        } else {
                            // this is the first update of the bundle
                            updatesForBundleKeys.put(key, update);
                            bundleUpdateLocations.put(bundle, newLocation);
                        }
                    }
                }
            } else {
                if (kind == PatchKind.ROLLUP) {
                    // we simply do not touch the bundle from patch - it is installed in KARAF_HOME/system
                    // and available to be installed when new features are detected
                } else {
                    // for non-rollup patches however, we signal an error - all bundles from P patch
                    // should be used to update already installed bundles
                    System.err.printf("Skipping bundle %s - unable to process bundle without a version range configuration%n", newLocation);
                }
            }
        }

        return updatesInThisPatch;
    }

    /**
     * Returns a list of {@link FeatureUpdate} for single patch, taking into account already discovered updates
     * @param patch
     * @param allFeatureRepositories
     * @param featureUpdateRepositories
     * @param singleFeatureUpdateRepositories
     * @param updatesForFeatureKeys
     * @param kind   @return
     */
    private List<FeatureUpdate> featureUpdatesInPatch(Patch patch,
                                                      Map<String, Repository> allFeatureRepositories,
                                                      Map<String, String> featureUpdateRepositories,
                                                      Map<String, String> singleFeatureUpdateRepositories,
                                                      Map<String, FeatureUpdate> updatesForFeatureKeys,
                                                      PatchKind kind) throws Exception {
        Set<String> addedRepositoryNames = new HashSet<>();
        HashMap<String, Repository> after = null;
        try {
            List<FeatureUpdate> updatesInThisPatch = new LinkedList<>();

            /*
             * Two pairs of features makes feature names not enough to be a key:
             * <feature name="openjpa" description="Apache OpenJPA 2.2.x persistent engine support" version="2.2.2" resolver="(obr)">
             * <feature name="openjpa" description="Apache OpenJPA 2.3.x persistence engine support" version="2.3.0" resolver="(obr)">
             * and
             * <feature name="activemq-camel" version="5.11.0.redhat-621039" resolver="(obr)" start-level="50">
             * <feature name="activemq-camel" version="1.2.0.redhat-621039" resolver="(obr)">
             */

            // install the new feature repos, tracking the set the were
            // installed before and after
            // (e.g, "karaf-enterprise-2.4.0.redhat-620133" -> Repository)
            Map<String, Repository> before = new HashMap<>(allFeatureRepositories);
            for (String url : patch.getPatchData().getFeatureFiles()) {
                featuresService.addRepository(new URI(url));
            }
            after = getAvailableFeatureRepositories();

            // track which old repos provide which features to find out if we have new repositories for those features
            // key is name|version (don't expect '|' to be part of name...)
            // assume that [feature-name, feature-version{major,minor,0,0}] is defined only in single repository
            Map<String, String> featuresInOldRepositories = new HashMap<>();
            // key is only name, without version - used when there's single feature in old and in new repositories
            MultiMap<String, String> singleFeaturesInOldRepositories = new MultiMap<>();
            Map<String, Version> actualOldFeatureVersions = new HashMap<>();
            for (Repository repository : before.values()) {
                for (Feature feature : repository.getFeatures()) {
                    Version v = Utils.getFeatureVersion(feature.getVersion());
                    Version lowestUpdateableVersion = new Version(v.getMajor(), v.getMinor(), 0);
                    // assume that we can update feature XXX-2.2.3 to XXX-2.2.142, but not to XXX-2.3.0.alpha-1
                    String key = String.format("%s|%s", feature.getName(), lowestUpdateableVersion.toString());
                    featuresInOldRepositories.put(key, repository.getName());
                    singleFeaturesInOldRepositories.put(feature.getName(), repository.getName());
                    actualOldFeatureVersions.put(key, v);
                }
            }

            // Use the before and after set to figure out which repos were added.
            addedRepositoryNames = new HashSet<>(after.keySet());
            addedRepositoryNames.removeAll(before.keySet());

            // track the new repositories where we can find old features
            Map<String, String> featuresInNewRepositories = new HashMap<>();
            MultiMap<String, String> singleFeaturesInNewRepositories = new MultiMap<>();
            Map<String, String> actualNewFeatureVersions = new HashMap<>();
            MultiMap<String, String> singleActualNewFeatureVersions = new MultiMap<>();

            // Figure out which old repos were updated:  Do they have feature
            // with the same name as one contained in a repo being added?
            // and do they have update'able version? (just like with bundles)
            HashSet<String> oldRepositoryNames = new HashSet<String>();
            for (String addedRepositoryName : addedRepositoryNames) {
                Repository added = after.get(addedRepositoryName);
                for (Feature feature : added.getFeatures()) {
                    Version v = Utils.getFeatureVersion(feature.getVersion());
                    Version lowestUpdateableVersion = new Version(v.getMajor(), v.getMinor(), 0);
                    String key = String.format("%s|%s", feature.getName(), lowestUpdateableVersion.toString());
                    featuresInNewRepositories.put(key, addedRepositoryName);
                    singleFeaturesInNewRepositories.put(feature.getName(), addedRepositoryName);
                    actualNewFeatureVersions.put(key, v.toString());
                    singleActualNewFeatureVersions.put(feature.getName(), v.toString());
                    String oldRepositoryWithUpdateableFeature = featuresInOldRepositories.get(key);
                    if (oldRepositoryWithUpdateableFeature == null
                            && singleFeaturesInOldRepositories.get(feature.getName()) != null) {
                        oldRepositoryWithUpdateableFeature = singleFeaturesInOldRepositories.get(feature.getName()).get(0);
                    }
                    if (oldRepositoryWithUpdateableFeature != null) {
                        oldRepositoryNames.add(oldRepositoryWithUpdateableFeature);
                    }
                }
            }

            // Now we know which are the old repos that have been udpated.
            // again assume that we can find ALL of the features from this old repository in a new repository
            // We need to uninstall them. Before we uninstall, track which features were installed.
            for (String oldRepositoryName : oldRepositoryNames) {
                Repository repository = before.get(oldRepositoryName);
                for (Feature feature : repository.getFeatures()) {
                    if (featuresService.isInstalled(feature)) {
                        Version v = Utils.getFeatureVersion(feature.getVersion());
                        Version lowestUpdateableVersion = new Version(v.getMajor(), v.getMinor(), 0);
                        String key = String.format("%s|%s", feature.getName(), lowestUpdateableVersion.toString());
                        String newRepository = featuresInNewRepositories.get(key);
                        String newVersion = actualNewFeatureVersions.get(key);
                        if (newRepository == null) {
                            // try looking up the feature without version - e.g., like in updating "transaction"
                            // feature from 1.1.1 to 1.3.0
                            if (singleFeaturesInOldRepositories.get(feature.getName()) != null
                                    && singleFeaturesInOldRepositories.get(feature.getName()).size() == 1
                                    && singleFeaturesInNewRepositories.get(feature.getName()) != null
                                    && singleFeaturesInNewRepositories.get(feature.getName()).size() == 1) {
                                newRepository = singleFeaturesInNewRepositories.get(feature.getName()).get(0);
                            }
                        }
                        if (newVersion == null) {
                            if (singleActualNewFeatureVersions.get(feature.getName()) != null
                                    && singleActualNewFeatureVersions.get(feature.getName()).size() == 1) {
                                newVersion = singleActualNewFeatureVersions.get(feature.getName()).get(0);
                            }
                        }
                        FeatureUpdate featureUpdate = new FeatureUpdate(feature.getName(),
                                after.get(oldRepositoryName).getURI().toString(),
                                feature.getVersion(),
                                after.get(newRepository).getURI().toString(),
                                newVersion);
                        updatesInThisPatch.add(featureUpdate);
                        // Merge result
                        FeatureUpdate oldUpdate = updatesForFeatureKeys.get(key);
                        if (oldUpdate != null) {
                            Version upv = VersionTable.getVersion(oldUpdate.getNewVersion());
                            Version newV = VersionTable.getVersion(actualNewFeatureVersions.get(key));
                            if (upv.compareTo(newV) < 0) {
                                // other patch contains newer update for the feature
                                updatesForFeatureKeys.put(key, featureUpdate);
                                featureUpdateRepositories.put(key, featuresInNewRepositories.get(key));
                            }
                        } else {
                            // this is the first update of the bundle
                            updatesForFeatureKeys.put(key, featureUpdate);
                            featureUpdateRepositories.put(key, featuresInNewRepositories.get(key));
                        }
                    }
                }
            }

            return updatesInThisPatch;
        } catch (Exception e) {
            throw new PatchException(e.getMessage(), e);
        } finally {
            // we'll add new feature repositories again. here we've added them only to track the updates
            if (addedRepositoryNames != null && after != null) {
                for (String repo : addedRepositoryNames) {
                    if (after.get(repo) != null) {
                        featuresService.removeRepository(after.get(repo).getURI(), false);
                    }
                }
            }
        }
    }

    /**
     * If patch contains migrator bundle, install it by dropping to <code>deploy</code> directory.
     * @param patch
     * @throws IOException
     */
    private void installMigratorBundle(Patch patch) throws IOException {
        if (patch.getPatchData().getMigratorBundle() != null) {
            Artifact artifact = mvnurlToArtifact(patch.getPatchData().getMigratorBundle(), true);
            if (artifact != null) {
                // Copy it to the deploy dir
                File src = new File(repository, artifact.getPath());
                File target = new File(Utils.getDeployDir(karafHome), artifact.getArtifactId() + ".jar");
                FileUtils.copyFile(src, target);
            }
        }
    }

    /**
     * Returns a map of bundles (symbolic name -> Bundle) that were installed in <em>classic way</em> - i.e.,
     * not using {@link FeaturesService}.
     * User may have installed other bundles, drop some to <code>deploy/</code>, etc, but these probably
     * are not handled by patch mechanism.
     * @param allBundles
     * @return
     */
    private Map<String, Bundle> getCoreBundles(Bundle[] allBundles) throws IOException {
        Map<String, Bundle> coreBundles = new HashMap<>();

        Properties props = new Properties();
        FileInputStream stream = new FileInputStream(new File(karafHome, "etc/startup.properties"));
        props.load(stream);
        Set<String> locations = new HashSet<>();
        for (String startupBundle : props.stringPropertyNames()) {
            locations.add(Utils.pathToMvnurl(startupBundle));
        }
        for (Bundle b : allBundles) {
            String symbolicName = Utils.stripSymbolicName(b.getSymbolicName());
            if ("org.apache.felix.framework".equals(symbolicName)) {
                coreBundles.put(symbolicName, b);
            } else if ("org.ops4j.pax.url.mvn".equals(symbolicName)) {
                // we could check if it's in etc/startup.properties, but we're 100% sure :)
                coreBundles.put(symbolicName, b);
            } else {
                // only if it's in etc/startup.properties
                if (locations.contains(b.getLocation())) {
                    coreBundles.put(symbolicName, b);
                }
            }
        }
        IOUtils.closeQuietly(stream);

        return coreBundles;
    }
    static class MultiMap<K,V>  {

        HashMap<K,ArrayList<V>> delegate = new HashMap<>();

        public List<V> get(Object key) {
            return delegate.get(key);
        }
        public void put(K key, V value) {
            ArrayList<V> list = delegate.get(key);
            if( list == null ) {
                list = new ArrayList<>();
                delegate.put(key, list);
            }
            list.add(value);
        }

    }

    /**
     * Returns currently installed feature repositories. If patch is not installed, we should have the same state
     * before&amp;after.
     * @return
     */
    private HashMap<String, Repository> getAvailableFeatureRepositories() {
        HashMap<String, Repository> before = new HashMap<String, Repository>();
        for (Repository repository : featuresService.listRepositories()) {
            before.put(repository.getName(), repository);
        }
        return before;
    }

    @Override
    public void rollback(final Patch patch, boolean force) throws PatchException {
        final PatchResult result = patch.getResult();
        if (result == null) {
            throw new PatchException("Patch " + patch.getPatchData().getId() + " is not installed");
        }

        // current state of the framework
        Bundle[] allBundles = bundleContext.getBundles();

        // check if all the bundles that were updated in patch are available (installed)
        List<BundleUpdate> badUpdates = new ArrayList<BundleUpdate>();
        for (BundleUpdate update : result.getBundleUpdates()) {
            boolean found = false;
            Version v = Version.parseVersion(update.getNewVersion());
            for (Bundle bundle : allBundles) {
                if (stripSymbolicName(bundle.getSymbolicName()).equals(stripSymbolicName(update.getSymbolicName()))
                        && bundle.getVersion().equals(v)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                badUpdates.add(update);
            }
        }
        if (!badUpdates.isEmpty() && !force) {
            StringBuilder sb = new StringBuilder();
            sb.append("Unable to rollback patch ").append(patch.getPatchData().getId()).append(" because of the following missing bundles:\n");
            for (BundleUpdate up : badUpdates) {
                sb.append("\t").append(up.getSymbolicName()).append("/").append(up.getNewVersion()).append("\n");
            }
            throw new PatchException(sb.toString());
        }

        // bundle -> old location of the bundle to downgrade from
        final Map<Bundle, String> toUpdate = new HashMap<Bundle, String>();
        for (BundleUpdate update : result.getBundleUpdates()) {
            Version v = Version.parseVersion(update.getNewVersion());
            for (Bundle bundle : allBundles) {
                if (stripSymbolicName(bundle.getSymbolicName()).equals(stripSymbolicName(update.getSymbolicName()))
                        && bundle.getVersion().equals(v)) {
                    toUpdate.put(bundle, update.getPreviousLocation());
                }
            }
        }

        // restore startup.properties and overrides.properties
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    PatchManagement pm = patchManagement;
                    applyChanges(toUpdate);

                    pm.rollback(result);
                } catch (Exception e) {
                    throw new PatchException("Unable to rollback patch " + patch.getPatchData().getId() + ": " + e.getMessage(), e);
                }
                ((Patch) patch).setResult(null);
                File file = new File(patchDir, result.getPatchData().getId() + ".patch.result");
                file.delete();
            }
        });
    }

    /**
     * Returns two element table: symbolic name and version
     * @param url
     * @return
     * @throws IOException
     */
    private String[] getBundleIdentity(String url) throws IOException {
        JarInputStream jis = new JarInputStream(new URL(url).openStream());
        jis.close();
        Manifest manifest = jis.getManifest();
        Attributes att = manifest != null ? manifest.getMainAttributes() : null;
        String sn = att != null ? att.getValue(Constants.BUNDLE_SYMBOLICNAME) : null;
        String vr = att != null ? att.getValue(Constants.BUNDLE_VERSION) : null;
        if (sn == null || vr == null) {
            return null;
        }
        return new String[] { sn, vr };
    }

    /**
     * <p>Returns a {@link VersionRange} that existing bundle has to satisfy in order to be updated to
     * <code>newVersion</code></p>
     * <p>If we're upgrading to <code>1.2.3</code>, existing bundle has to be in range
     * <code>[1.2.0,1.2.3)</code></p>
     * @param patch
     * @param url
     * @param newVersion
     * @return
     */
    private VersionRange getUpdateableRange(Patch patch, String url, Version newVersion) {
        VersionRange range = null;
        if (patch.getPatchData().getVersionRange(url) == null) {
            // default version range starts with x.y.0 as the lower bound
            Version lower = new Version(newVersion.getMajor(), newVersion.getMinor(), 0);

            // We can't really upgrade with versions such as 2.1.0
            if (newVersion.compareTo(lower) > 0) {
                range = new VersionRange(false, lower, newVersion, true);
            }
        } else {
            range = new VersionRange(patch.getPatchData().getVersionRange(url));
        }

        return range;
    }

    private File getPatchStorage(Patch patch) {
        return new File(patchDir, patch.getPatchData().getId());
    }

    private void applyChanges(Map<Bundle, String> toUpdate) throws BundleException, IOException {
        List<Bundle> toStop = new ArrayList<Bundle>();
        Map<Bundle, String> lessToUpdate = new HashMap<>();
        for (Bundle b : toUpdate.keySet()) {
            if (b.getState() != Bundle.UNINSTALLED) {
                toStop.add(b);
                lessToUpdate.put(b, toUpdate.get(b));
            }
        }
        while (!toStop.isEmpty()) {
            List<Bundle> bs = getBundlesToDestroy(toStop);
            for (Bundle bundle : bs) {
                String hostHeader = (String) bundle.getHeaders().get(Constants.FRAGMENT_HOST);
                if (hostHeader == null && (bundle.getState() == Bundle.ACTIVE || bundle.getState() == Bundle.STARTING)) {
                    if (!"org.ops4j.pax.url.mvn".equals(bundle.getSymbolicName())) {
                        bundle.stop();
                    }
                }
                toStop.remove(bundle);
            }
        }
        Set<Bundle> toRefresh = new HashSet<Bundle>();
        Set<Bundle> toStart = new HashSet<Bundle>();
        for (Map.Entry<Bundle, String> e : lessToUpdate.entrySet()) {
            Bundle bundle = e.getKey();
            if (!"org.ops4j.pax.url.mvn".equals(bundle.getSymbolicName())) {
                System.out.println("updating: " + bundle.getSymbolicName());
                try {
                    BundleUtils.update(bundle, new URL(e.getValue()));
                } catch (BundleException ex) {
                    System.err.println("Failed to update: " + bundle.getSymbolicName()+", due to: "+e);
                }
                toStart.add(bundle);
            }
            toRefresh.add(bundle);
        }
        findBundlesWithOptionalPackagesToRefresh(toRefresh);
        findBundlesWithFragmentsToRefresh(toRefresh);
        if (!toRefresh.isEmpty()) {
            final CountDownLatch l = new CountDownLatch(1);
            FrameworkListener listener = new FrameworkListener() {
                @Override
                public void frameworkEvent(FrameworkEvent event) {
                    l.countDown();
                }
            };
            FrameworkWiring wiring = (FrameworkWiring) bundleContext.getBundle(0).adapt(FrameworkWiring.class);
            wiring.refreshBundles((Collection<Bundle>) toRefresh, listener);
            try {
                l.await();
            } catch (InterruptedException e) {
                throw new PatchException("Bundle refresh interrupted", e);
            }
        }
        for (Bundle bundle : toStart) {
            String hostHeader = (String) bundle.getHeaders().get(Constants.FRAGMENT_HOST);
            if (hostHeader == null) {
                try {
                    bundle.start();
                } catch (BundleException e) {
                    System.err.println("Failed to start: " + bundle.getSymbolicName()+", due to: "+e);
                }
            }
        }
    }

    private List<Bundle> getBundlesToDestroy(List<Bundle> bundles) {
        List<Bundle> bundlesToDestroy = new ArrayList<Bundle>();
        for (Bundle bundle : bundles) {
            ServiceReference[] references = bundle.getRegisteredServices();
            int usage = 0;
            if (references != null) {
                for (ServiceReference reference : references) {
                    usage += getServiceUsage(reference, bundles);
                }
            }
            if (usage == 0) {
                bundlesToDestroy.add(bundle);
            }
        }
        if (!bundlesToDestroy.isEmpty()) {
            Collections.sort(bundlesToDestroy, new Comparator<Bundle>() {
                public int compare(Bundle b1, Bundle b2) {
                    return (int) (b2.getLastModified() - b1.getLastModified());
                }
            });
        } else {
            ServiceReference ref = null;
            for (Bundle bundle : bundles) {
                ServiceReference[] references = bundle.getRegisteredServices();
                for (ServiceReference reference : references) {
                    if (getServiceUsage(reference, bundles) == 0) {
                        continue;
                    }
                    if (ref == null || reference.compareTo(ref) < 0) {
                        ref = reference;
                    }
                }
            }
            if (ref != null) {
                bundlesToDestroy.add(ref.getBundle());
            }
        }
        return bundlesToDestroy;
    }

    private static int getServiceUsage(ServiceReference ref, List<Bundle> bundles) {
        Bundle[] usingBundles = ref.getUsingBundles();
        int nb = 0;
        if (usingBundles != null) {
            for (Bundle bundle : usingBundles) {
                if (bundles.contains(bundle)) {
                    nb++;
                }
            }
        }
        return nb;
    }

    protected void findBundlesWithFragmentsToRefresh(Set<Bundle> toRefresh) {
        for (Bundle b : toRefresh) {
            if (b.getState() != Bundle.UNINSTALLED) {
                String hostHeader = (String) b.getHeaders().get(Constants.FRAGMENT_HOST);
                if (hostHeader != null) {
                    Clause[] clauses = Parser.parseHeader(hostHeader);
                    if (clauses != null && clauses.length > 0) {
                        Clause path = clauses[0];
                        for (Bundle hostBundle : bundleContext.getBundles()) {
                            if (hostBundle.getSymbolicName().equals(path.getName())) {
                                String ver = path.getAttribute(Constants.BUNDLE_VERSION_ATTRIBUTE);
                                if (ver != null) {
                                    VersionRange v = VersionRange.parseVersionRange(ver);
                                    if (v.contains(hostBundle.getVersion())) {
                                        toRefresh.add(hostBundle);
                                    }
                                } else {
                                    toRefresh.add(hostBundle);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    protected void findBundlesWithOptionalPackagesToRefresh(Set<Bundle> toRefresh) {
        // First pass: include all bundles contained in these features
        Set<Bundle> bundles = new HashSet<Bundle>(Arrays.asList(bundleContext.getBundles()));
        bundles.removeAll(toRefresh);
        if (bundles.isEmpty()) {
            return;
        }
        // Second pass: for each bundle, check if there is any unresolved optional package that could be resolved
        Map<Bundle, List<Clause>> imports = new HashMap<Bundle, List<Clause>>();
        for (Iterator<Bundle> it = bundles.iterator(); it.hasNext();) {
            Bundle b = it.next();
            String importsStr = (String) b.getHeaders().get(Constants.IMPORT_PACKAGE);
            List<Clause> importsList = getOptionalImports(importsStr);
            if (importsList.isEmpty()) {
                it.remove();
            } else {
                imports.put(b, importsList);
            }
        }
        if (bundles.isEmpty()) {
            return;
        }
        // Third pass: compute a list of packages that are exported by our bundles and see if
        //             some exported packages can be wired to the optional imports
        List<Clause> exports = new ArrayList<Clause>();
        for (Bundle b : toRefresh) {
            if (b.getState() != Bundle.UNINSTALLED) {
                String exportsStr = (String) b.getHeaders().get(Constants.EXPORT_PACKAGE);
                if (exportsStr != null) {
                    Clause[] exportsList = Parser.parseHeader(exportsStr);
                    exports.addAll(Arrays.asList(exportsList));
                }
            }
        }
        for (Iterator<Bundle> it = bundles.iterator(); it.hasNext();) {
            Bundle b = it.next();
            List<Clause> importsList = imports.get(b);
            for (Iterator<Clause> itpi = importsList.iterator(); itpi.hasNext();) {
                Clause pi = itpi.next();
                boolean matching = false;
                for (Clause pe : exports) {
                    if (pi.getName().equals(pe.getName())) {
                        String evStr = pe.getAttribute(Constants.VERSION_ATTRIBUTE);
                        String ivStr = pi.getAttribute(Constants.VERSION_ATTRIBUTE);
                        Version exported = evStr != null ? Version.parseVersion(evStr) : Version.emptyVersion;
                        VersionRange imported = ivStr != null ? VersionRange.parseVersionRange(ivStr) : VersionRange.ANY_VERSION;
                        if (imported.contains(exported)) {
                            matching = true;
                            break;
                        }
                    }
                }
                if (!matching) {
                    itpi.remove();
                }
            }
            if (importsList.isEmpty()) {
                it.remove();
            }
        }
        toRefresh.addAll(bundles);
    }

    protected List<Clause> getOptionalImports(String importsStr) {
        Clause[] imports = Parser.parseHeader(importsStr);
        List<Clause> result = new LinkedList<Clause>();
        for (Clause anImport : imports) {
            String resolution = anImport.getDirective(Constants.RESOLUTION_DIRECTIVE);
            if (Constants.RESOLUTION_OPTIONAL.equals(resolution)) {
                result.add(anImport);
            }
        }
        return result;
    }

    /*
     * Create a bundle version history based on the information in the .patch and .patch.result files
     */
    protected BundleVersionHistory createBundleVersionHistory() {
        return new BundleVersionHistory(load(true));
    }

    /**
     * Check if the set of patches mixes P and R patches. We can install several {@link PatchKind#NON_ROLLUP}
     * patches at once, but only one {@link PatchKind#ROLLUP} patch.
     * @param patches
     * @return kind of patches in the set
     */
    private PatchKind checkConsistency(Collection<Patch> patches) throws PatchException {
        boolean hasP = false, hasR = false;
        for (Patch patch : patches) {
            if (patch.getPatchData().isRollupPatch()) {
                if (hasR) {
                    throw new PatchException("Can't install more than one rollup patch at once");
                }
                hasR = true;
            } else {
                hasP = true;
            }
        }
        if (hasR && hasP) {
            throw new PatchException("Can't install both rollup and non-rollup patches in single run");
        }

        return hasR ? PatchKind.ROLLUP : PatchKind.NON_ROLLUP;
    }

    /**
     * Check if the requirements for all specified patches have been installed
     * @param patches the set of patches to check
     * @throws PatchException if at least one of the patches has missing requirements
     */
    protected void checkPrerequisites(Collection<Patch> patches) throws PatchException {
        for (Patch patch : patches) {
            checkPrerequisites(patch);
        }
    }

    /**
     * Check if the requirements for the specified patch have been installed
     * @param patch the patch to check
     * @throws PatchException if the requirements for the patch are missing or not yet installed
     */
    protected void checkPrerequisites(Patch patch) throws PatchException {
        for (String requirement : patch.getPatchData().getRequirements()) {
            Patch required = getPatch(requirement);
            if (required == null) {
                throw new PatchException(String.format("Required patch '%s' is missing", requirement));
            }
            if (!required.isInstalled()) {
                throw new PatchException(String.format("Required patch '%s' is not installed", requirement));
            }
        }
    }

    /**
     * Contains the history of bundle versions that have been applied through the patching mechanism
     */
    protected static final class BundleVersionHistory {

        // symbolic name -> version -> location
        private Map<String, Map<String, String>> bundleVersions = new HashMap<String, Map<String, String>>();

        public BundleVersionHistory(Map<String, Patch> patches) {
            for (Map.Entry<String, Patch> patch : patches.entrySet()) {
                PatchResult result = patch.getValue().getResult();
                if (result != null) {
                    for (BundleUpdate update : result.getBundleUpdates()) {
                        String symbolicName = stripSymbolicName(update.getSymbolicName());
                        Map<String, String> versions = bundleVersions.get(symbolicName);
                        if (versions == null) {
                            versions = new HashMap<String, String>();
                            bundleVersions.put(symbolicName, versions);
                        }
                        versions.put(update.getNewVersion(), update.getNewLocation());
                    }
                }
            }
        }

        /**
         * Get the bundle location for a given bundle version.  If this bundle version was not installed through a patch,
         * this methods will return the original bundle location.
         *
         * @param bundle the bundle
         * @return the location for this bundle version
         */
        protected String getLocation(Bundle bundle) {
            String symbolicName = stripSymbolicName(bundle.getSymbolicName());
            Map<String, String> versions = bundleVersions.get(symbolicName);
            String location = null;
            if (versions != null) {
                location = versions.get(bundle.getVersion().toString());
            }
            if (location == null) {
                location = bundle.getLocation();
            }
            return location;
        }
    }

}
