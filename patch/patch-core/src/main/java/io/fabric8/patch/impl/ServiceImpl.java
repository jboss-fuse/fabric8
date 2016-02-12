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
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import io.fabric8.patch.Service;
import io.fabric8.patch.management.Artifact;
import io.fabric8.patch.management.BackupService;
import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.FeatureUpdate;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchData;
import io.fabric8.patch.management.PatchDetailsRequest;
import io.fabric8.patch.management.PatchException;
import io.fabric8.patch.management.PatchKind;
import io.fabric8.patch.management.PatchManagement;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.patch.management.Pending;
import io.fabric8.patch.management.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.utils.manifest.Clause;
import org.apache.felix.utils.manifest.Parser;
import org.apache.felix.utils.version.VersionRange;
import org.apache.felix.utils.version.VersionTable;
import org.apache.karaf.features.BundleInfo;
import org.apache.karaf.features.Conditional;
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
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.component.ComponentContext;

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

    @Reference(referenceInterface = BackupService.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.STATIC)
    private BackupService backupService;

    private File karafHome;
    // by default it's ${karaf.home}/system
    private File repository;

    private OSGiPatchHelper helper;

    @Activate
    void activate(ComponentContext componentContext) throws IOException {
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
                if (patchManagement.isStandaloneChild()) {
                    patchDir = new File(System.getProperty("karaf.home"), "patches");
                }
                if (patchDir == null) {
                    // only now fallback to datafile of system bundle
                    patchDir = this.bundleContext.getDataFile("patches");
                }
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
        helper = new OSGiPatchHelper(karafHome, bundleContext);

        load(true);

        resumePendingPatchTasks();
    }

    /**
     * Upon startup (activation), we check if there are any *.patch.pending files. if yes, we're finishing the
     * installation
     */
    private void resumePendingPatchTasks() throws IOException {
        File[] pendingPatches = patchDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.exists() && pathname.getName().endsWith(".pending");
            }
        });
        for (File pending : pendingPatches) {
            Pending what = Pending.valueOf(FileUtils.readFileToString(pending));

            File patchFile = new File(pending.getParentFile(), pending.getName().replaceFirst("\\.pending$", ""));
            PatchData patchData = PatchData.load(new FileInputStream(patchFile));
            Patch patch = patchManagement.loadPatch(new PatchDetailsRequest(patchData.getId()));
            System.out.printf("Resume %s of %spatch \"%s\"%n",
                    what == Pending.ROLLUP_INSTALLATION ? "installation" : "rollback",
                    patch.getPatchData().isRollupPatch() ? "rollup " : "",
                    patch.getPatchData().getId());

            // feature time

            Set<String> newRepositories = new LinkedHashSet<>();
            Set<String> features = new LinkedHashSet<>();
            for (FeatureUpdate featureUpdate : patch.getResult().getFeatureUpdates()) {
                if (featureUpdate.getName() == null && featureUpdate.getPreviousRepository() != null) {
                    // feature was not shipped by patch
                    newRepositories.add(featureUpdate.getPreviousRepository());
                } else  if (featureUpdate.getNewRepository() == null) {
                    // feature was not changed by patch
                    newRepositories.add(featureUpdate.getPreviousRepository());
                    features.add(String.format("%s|%s", featureUpdate.getName(), featureUpdate.getPreviousVersion()));
                } else {
                    // feature was shipped by patch
                    if (what == Pending.ROLLUP_INSTALLATION) {
                        newRepositories.add(featureUpdate.getNewRepository());
                        features.add(String.format("%s|%s", featureUpdate.getName(), featureUpdate.getNewVersion()));
                    } else {
                        newRepositories.add(featureUpdate.getPreviousRepository());
                        features.add(String.format("%s|%s", featureUpdate.getName(), featureUpdate.getPreviousVersion()));
                    }
                }
            }
            for (String repo : newRepositories) {
                System.out.println("Restoring feature repository: " + repo);
                try {
                    featuresService.addRepository(URI.create(repo));
                } catch (Exception e) {
                    System.err.println(e.getMessage());
                    e.printStackTrace(System.err);
                    System.err.flush();
                }
            }
            for (String f : features) {
                String[] fv = f.split("\\|");
                System.out.printf("Restoring feature %s/%s%n", fv[0], fv[1]);
                try {
                    featuresService.installFeature(fv[0], fv[1]);
                } catch (Exception e) {
                    System.err.println(e.getMessage());
                    e.printStackTrace(System.err);
                    System.err.flush();
                }
            }

            // bundle time

            for (BundleUpdate update : patch.getResult().getBundleUpdates()) {
                if (!update.isIndependent()) {
                    continue;
                }
                String location = null;
                if (update.getNewVersion() == null) {
                    System.out.printf("Restoring bundle %s from %s%n", update.getSymbolicName(), update.getPreviousLocation());
                    location = update.getPreviousLocation();
                } else {
                    if (what == Pending.ROLLUP_INSTALLATION) {
                        System.out.printf("Updating bundle %s from %s%n", update.getSymbolicName(), update.getNewLocation());
                        location = update.getNewLocation();
                    } else {
                        System.out.printf("Downgrading bundle %s from %s%n", update.getSymbolicName(), update.getPreviousLocation());
                        location = update.getPreviousLocation();
                    }
                }

                try {
                    Bundle b = bundleContext.installBundle(location);
                    if (update.getStartLevel() > -1) {
                        b.adapt(BundleStartLevel.class).setStartLevel(update.getStartLevel());
                    }
                    switch (update.getState()) {
                        case Bundle.UNINSTALLED: // ?
                        case Bundle.INSTALLED:
                        case Bundle.STARTING:
                        case Bundle.STOPPING:
                            break;
                        case Bundle.RESOLVED:
                            // ?bundleContext.getBundle(0L).adapt(org.osgi.framework.wiring.FrameworkWiring.class).resolveBundles(...);
                            break;
                        case Bundle.ACTIVE:
                            b.start();
                            break;
                    }
                } catch (BundleException e) {
                    System.err.println(" - " + e.getMessage());
//                    e.printStackTrace(System.err);
                    System.err.flush();
                }
            }

            pending.delete();
            System.out.printf("%spatch \"%s\" %s successfully%n",
                    patch.getPatchData().isRollupPatch() ? "Rollup " : "",
                    patchData.getId(),
                    what == Pending.ROLLUP_INSTALLATION ? "installed" : "rolled back");
            if (what == Pending.ROLLUP_ROLLBACK) {
                List<String> bases = patch.getResult().getKarafBases();
                for (Iterator<String> iterator = bases.iterator(); iterator.hasNext(); ) {
                    String s = iterator.next();
                    if (s.startsWith(System.getProperty("karaf.name"))) {
                        iterator.remove();
                    }
                }
                patch.getResult().setPending(null);
                patch.getResult().store();
                if (patch.getResult().getKarafBases().size() == 0) {
                    File file = new File(patchDir, patchData.getId() + ".patch.result");
                    file.delete();
                }
            }
        }
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
        if ("file".equals(url.getProtocol())) {
            // ENTESB-4992: prevent adding non existing files or directories
            try {
                if (!new File(url.toURI()).isFile()) {
                    throw new PatchException("Path " + url.getPath() + " doesn't exist or is not a file");
                }
            } catch (URISyntaxException e) {
                throw new PatchException(e.getMessage(), e);
            }
        }
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
     * <p>Static changes are handled by git, runtime changes (bundles, features) are handled depending on patch type:<ul>
     *     <li>Rollup: clear OSGi bundle cache, reinstall features that were installed after restart</li>
     *     <li>Non-Rollup: update bundles, generate overrides.properties and update scripts to reference new versions</li>
     * </ul></p>
     * <p>For Rollup patches we don't update bundles - we clear the bundle cache instead.</p>
     * @param patches
     * @param simulate
     * @param synchronous
     * @return
     */
    private Map<String, PatchResult> install(final Collection<Patch> patches, final boolean simulate, boolean synchronous) {
        PatchKind kind = checkConsistency(patches);
        checkPrerequisites(patches);
//        checkFabric();
        String transaction = null;

        try {
            // Compute individual patch results (patchId -> Result)
            final Map<String, PatchResult> results = new LinkedHashMap<String, PatchResult>();

            // current state of the framework
            Bundle[] allBundles = bundleContext.getBundles();

            // bundle -> url to update the bundle from (used for non-rollup patch)
            final Map<Bundle, String> bundleUpdateLocations = new HashMap<>();

            /* A "key" is name + "update'able version". Such version is current version with micro version == 0 */

            // [symbolic name|updateable-version] -> newest update for the bundle out of all installed patches
            final Map<String, BundleUpdate> updatesForBundleKeys = new LinkedHashMap<>();
            // [feature name|updateable-version] -> newest update for the feature out of all installed patches
            final Map<String, FeatureUpdate> updatesForFeatureKeys = new LinkedHashMap<>();

            // symbolic name -> version -> location
            final BundleVersionHistory history = createBundleVersionHistory();

            // beginning installation transaction = creating of temporary branch in git
            transaction = this.patchManagement.beginInstallation(kind);

            // bundles from etc/startup.properties + felix.framework = all bundles not managed by features
            // these bundles will be treated in special way
            // symbolic name -> Bundle
            final Map<String, Bundle> coreBundles = helper.getCoreBundles(allBundles);

            // collect runtime information from patches (features, bundles) and static information (files)
            // runtime info is prepared to apply runtime changes and static info is prepared to update KARAF_HOME files
            for (Patch patch : patches) {
                List<FeatureUpdate> featureUpdatesInThisPatch = null;
                if (kind == PatchKind.ROLLUP) {
                    // list of feature updates for the current patch
                    featureUpdatesInThisPatch = featureUpdatesInPatch(patch, updatesForFeatureKeys, kind);

                    helper.sortFeatureUpdates(featureUpdatesInThisPatch);
                }

                // list of bundle updates for the current patch - for ROLLUP patch, we minimize the list of bundles
                // to "restore" (install after clearing data/cache) by not including bundles that are
                // already updated as part of fueatures update
                List<BundleUpdate> bundleUpdatesInThisPatch = bundleUpdatesInPatch(patch, allBundles,
                        bundleUpdateLocations, history, updatesForBundleKeys, kind, coreBundles,
                        featureUpdatesInThisPatch);

                // each patch may change files, we're not updating the main files yet - it'll be done when
                // install transaction is committed
                patchManagement.install(transaction, patch, bundleUpdatesInThisPatch);

                // each patch may ship a migrator
                if (!simulate) {
                    installMigratorBundle(patch);
                }

                // prepare patch result before doing runtime changes
                PatchResult result = null;
                if (patch.getResult() != null) {
                    result = patch.getResult();
                } else {
                    result = new PatchResult(patch.getPatchData(), simulate, System.currentTimeMillis(),
                            bundleUpdatesInThisPatch, featureUpdatesInThisPatch);
                }
                result.getKarafBases().add(String.format("%s | %s",
                        System.getProperty("karaf.name"), System.getProperty("karaf.base")));
                results.put(patch.getPatchData().getId(), result);
            }

            // We don't have to update bundles that are uninstalled anyway when uninstalling features we
            // are updating (updating a feature = uninstall + install)
            // When feature is uninstalled, its bundles may get uninstalled too, if they are not referenced
            // from any other feature, including special (we're implementation aware!) "startup" feature
            // that is created during initailization of FeaturesService. As expected, this feature contains
            // all bundles started by other means which is:
            // - felix.framework (system bundle)
            // - all bundles referenced in etc/startup.properties

            // One special case
            if (kind == PatchKind.NON_ROLLUP) {
                // for rollup patch, this bundle will be installed from scratch
                for (Map.Entry<Bundle, String> entry : bundleUpdateLocations.entrySet()) {
                    Bundle bundle = entry.getKey();
                    if ("org.ops4j.pax.url.mvn".equals(stripSymbolicName(bundle.getSymbolicName()))) {
                        // handle this bundle specially - update it here
                        URL location = new URL(entry.getValue());
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
            }

            Presentation.displayFeatureUpdates(updatesForFeatureKeys.values(), true);

            // effectively, we will update all the bundles from this list - even if some bundles will be "updated"
            // as part of feature installation
            Presentation.displayBundleUpdates(updatesForBundleKeys.values(), true);

            // now, if we're installing rollup patch, we just restart with clean cache
            // then required repositories, features and bundles will be reinstalled
            if (kind == PatchKind.ROLLUP) {
                if (!simulate) {
                    if (patches.size() == 1) {
                        Patch patch = patches.iterator().next();
                        PatchResult result = results.get(patch.getPatchData().getId());
                        patch.setResult(result);
                        result.setPending(Pending.ROLLUP_INSTALLATION);
                        result.store();

                        // backup all datafiles of all bundles - we we'll backup configadmin configurations in
                        // single shot
                        backupService.backupDataFiles(result, Pending.ROLLUP_INSTALLATION);

                        for (Bundle b : coreBundles.values()) {
                            if (Utils.stripSymbolicName(b.getSymbolicName()).equals("org.apache.felix.fileinstall")) {
                                b.stop(Bundle.STOP_TRANSIENT);
                                break;
                            }
                        }

                        // update KARAF_HOME
                        patchManagement.commitInstallation(transaction);

                        // Some updates need a full JVM restart.
                        if( isJvmRestartNeeded(results) ) {
                            boolean handlesFullRestart = Boolean.getBoolean("karaf.restart.jvm.supported");
                            if (handlesFullRestart) {
                                System.out.println("Rollup patch " + patch.getPatchData().getId() + " installed. Restarting Karaf..");
                                System.setProperty("karaf.restart.jvm", "true");
                            } else {
                                System.out.println("Rollup patch " + patch.getPatchData().getId() + " installed. Shutting down Karaf, please restart...");
                            }
                        } else {
                            // We don't need a JVM restart, so lets just do a OSGi framework restart
                            System.setProperty("karaf.restart", "true");
                        }

                        File karafData = new File(bundleContext.getProperty("karaf.data"));
                        File cleanCache = new File(karafData, "clean_cache");
                        cleanCache.createNewFile();
                        Thread.currentThread().setContextClassLoader(bundleContext.getBundle(0l).adapt(BundleWiring.class).getClassLoader());
                        bundleContext.getBundle(0l).stop();
                    }
                } else {
                    System.out.println("Simulation only - no files and runtime data will be modified.");
                    patchManagement.rollbackInstallation(transaction);
                }

                return results;
            }

            // continue with NON_ROLLUP patch

            // update KARAF_HOME
            if (!simulate) {
                patchManagement.commitInstallation(transaction);
            } else {
                patchManagement.rollbackInstallation(transaction);
            }

            if (!simulate) {
                Runnable task = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // update bundles
                            applyChanges(bundleUpdateLocations);

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
                if (synchronous) {
                    task.run();
                } else {
                    new Thread(task).start();
                }
            } else {
                System.out.println("Simulation only - no files and runtime data will be modified.");
            }

            return results;
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.err.flush();

            if (transaction != null && patchManagement != null) {
                patchManagement.rollbackInstallation(transaction);
            }
            throw new PatchException(e.getMessage(), e);
        } finally {
            System.out.flush();
        }
    }

    /**
     * Sanity check - patches can't be <code>patch:install</code>ed or <code>patch:rollback</code>ed in fabric env.
     */
    private void checkFabric() {
        ServiceReference<?> reference = bundleContext.getServiceReference("io.fabric8.api.FabricService");
        if (reference != null && bundleContext.getService(reference) != null) {
            throw new UnsupportedOperationException("In fabric mode patches can be managed only with 'patch:fabric-install' command");
        }
    }

    private boolean isJvmRestartNeeded(Map<String, PatchResult> results) {
        for (PatchResult result : results.values()) {
            if (isJvmRestartNeeded(result)) {
                return true;
            }
        }
        return false;
    }

    private boolean isJvmRestartNeeded(PatchResult result) {
        for (String file : result.getPatchData().getFiles()) {
            if (file.startsWith("lib/") || file.equals("etc/jre.properties")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a list of {@link BundleUpdate} for single patch, taking into account already discovered updates
     * @param patch
     * @param allBundles
     * @param bundleUpdateLocations out parameter that gathers update locations for bundles across patches
     * @param history
     * @param updatesForBundleKeys
     * @param kind
     * @param coreBundles
     * @param featureUpdatesInThisPatch
     * @return
     * @throws IOException
     */
    private List<BundleUpdate> bundleUpdatesInPatch(Patch patch,
                                                    Bundle[] allBundles,
                                                    Map<Bundle, String> bundleUpdateLocations,
                                                    BundleVersionHistory history,
                                                    Map<String, BundleUpdate> updatesForBundleKeys,
                                                    PatchKind kind,
                                                    Map<String, Bundle> coreBundles,
                                                    List<FeatureUpdate> featureUpdatesInThisPatch) throws Exception {

        List<BundleUpdate> updatesInThisPatch = new LinkedList<>();

        // for ROLLUP patch we can check which bundles AREN'T updated by this patch - we have to reinstall them
        // at the same version as existing one. "no update" means "require install after clearing cache"
        // Initially all bundles need update. If we find an update in patch, we remove a key from this map
        Map<String, Bundle> updateNotRequired = new LinkedHashMap<>();
//        // let's keep {symbolic name -> list of versions} mapping
//        MultiMap<String, Version> allBundleVersions = new MultiMap<>();
        // bundle location -> bundle key (symbolic name|updateable version)
        Map<String, String> locationsOfBundleKeys = new HashMap<>();

        for (Bundle b : allBundles) {
            Version v = b.getVersion();
            Version updateableVersion = new Version(v.getMajor(), v.getMinor(), 0);
            String key = String.format("%s|%s", stripSymbolicName(b.getSymbolicName()), updateableVersion.toString());
            // we can't key by symbolic name only - we could miss all those Guavas installed...
            // but we definitely won't handle the situation where we have two bundles installed with the same
            // symbolic name, differing at micro version only
            if (!coreBundles.containsKey(stripSymbolicName(b.getSymbolicName()))) {
                updateNotRequired.put(key, b);
            } else {
                // let's key core (etc/startup.properties) bundles by symbolic name only - there should be only
                // one version of symbolic name
                updateNotRequired.put(stripSymbolicName(b.getSymbolicName()), b);
            }
//            allBundleVersions.put(stripSymbolicName(b.getSymbolicName()), b.getVersion());
            locationsOfBundleKeys.put(b.getLocation(), key);
        }

        // let's prepare a set of bundle keys that are part of features that will be updated/reinstalled - those
        // bundle keys don't have to be reinstalled separately
        Set<String> bundleKeysFromFeatures = new HashSet<>();
        if (featureUpdatesInThisPatch != null) {
            for (FeatureUpdate featureUpdate : featureUpdatesInThisPatch) {
                if (featureUpdate.getName() != null) {
                    // this is either installation or update of single feature
                    String fName = featureUpdate.getName();
                    String fVersion = featureUpdate.getPreviousVersion();
                    Feature f = featuresService.getFeature(fName, fVersion);
                    for (BundleInfo bundleInfo : f.getBundles()) {
                        if (!bundleInfo.isDependency() && locationsOfBundleKeys.containsKey(bundleInfo.getLocation())) {
                            bundleKeysFromFeatures.add(locationsOfBundleKeys.get(bundleInfo.getLocation()));
                        }
                    }
                    for (Conditional cond : f.getConditional()) {
                        for (BundleInfo bundleInfo : cond.getBundles()) {
                            if (!bundleInfo.isDependency() && locationsOfBundleKeys.containsKey(bundleInfo.getLocation())) {
                                bundleKeysFromFeatures.add(locationsOfBundleKeys.get(bundleInfo.getLocation()));
                            }
                        }
                    }
                }
            }
        }

        for (String newLocation : patch.getPatchData().getBundles()) {
            // [symbolicName, version] of the new bundle
            String[] symbolicNameVersion = helper.getBundleIdentity(newLocation);
            if (symbolicNameVersion == null) {
                continue;
            }
            String sn = stripSymbolicName(symbolicNameVersion[0]);
            String vr = symbolicNameVersion[1];
            Version newVersion = VersionTable.getVersion(vr);
            Version updateableVersion = new Version(newVersion.getMajor(), newVersion.getMinor(), 0);
            // this bundle update from a patch may be applied only to relevant bundle|updateable-version, not to
            // *every* bundle with exact symbolic name
            String key = null;
            if (!coreBundles.containsKey(sn)) {
                key = String.format("%s|%s", sn, updateableVersion.toString());
            } else {
                key = sn;
            }

            // if existing bundle is within this range, update is possible
            VersionRange range = getUpdateableRange(patch, newLocation, newVersion);
            if (coreBundles.containsKey(sn)) {
                // for core bundles, we don't want to miss the update - for example fileinstall 3.4.2->3.5.0
                // so we lower down the lowest possible version of core bundle that we can update
                if (range == null) {
                    range = new VersionRange(false, Version.emptyVersion, newVersion, true);
                } else {
                    range = new VersionRange(false, Version.emptyVersion, range.getCeiling(), true);
                }
            } else if (range != null) {
                // if range is specified on non core bundle, the key should be different - updateable
                // version should be taken from range
                key = String.format("%s|%s", sn, range.getFloor().toString());
            }

            Bundle bundle = updateNotRequired.get(key);
            if (bundle == null && coreBundles.containsKey(sn)) {
                bundle = updateNotRequired.get(sn);
            }
            if (bundle == null || range == null) {
                // this patch ships a bundle that can't be used as an update for ANY currently installed bundle
                if (kind == PatchKind.NON_ROLLUP) {
                    // which is strange, because non rollup patches should update existing bundles...
                    if (range == null) {
                        System.err.printf("Skipping bundle %s - unable to process bundle without a version range configuration%n", newLocation);
                    } else {
                        // range is fine, we simply didn't find installed bundle at all - bundle from patch
                        // will be stored in ${karaf.default.repository}, but not used as an update
                    }
                }
                continue;
            }

            Version oldVersion = bundle.getVersion();
            if (range.contains(oldVersion)) {
                String oldLocation = history.getLocation(bundle);
                if ("org.ops4j.pax.url.mvn".equals(sn)) {
                    Artifact artifact = Utils.mvnurlToArtifact(newLocation, true);
                    if (artifact != null) {
                        URL location = new File(repository,
                                String.format("org/ops4j/pax/url/pax-url-aether/%1$s/pax-url-aether-%1$s.jar",
                                        artifact.getVersion())).toURI().toURL();
                        newLocation = location.toString();
                    }
                }

                int startLevel = bundle.adapt(BundleStartLevel.class).getStartLevel();
                int state = bundle.getState();

                BundleUpdate update = new BundleUpdate(sn, newVersion.toString(), newLocation,
                        oldVersion.toString(), oldLocation, startLevel, state);
                if (bundleKeysFromFeatures.contains(key) || coreBundles.containsKey(sn)) {
                    update.setIndependent(false);
                }
                updatesInThisPatch.add(update);
                updateNotRequired.remove(key);
                if (coreBundles.containsKey(sn)) {
                    updateNotRequired.remove(sn);
                }
                // Merge result
                BundleUpdate oldUpdate = updatesForBundleKeys.get(key);
                if (oldUpdate != null) {
                    Version upv = null;
                    if (oldUpdate.getNewVersion() != null) {
                        upv = VersionTable.getVersion(oldUpdate.getNewVersion());
                    }
                    if (upv == null || upv.compareTo(newVersion) < 0) {
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

        if (kind == PatchKind.ROLLUP) {
            // we have to detect the bundles that have no available update in the patch, or at least doesn't have
            // corresponding symbolic name among bundles from patch
            // those bundles are either bundles that don't need update or areuser bundles (which may be part of
            // user features) and we have (at least try) to install them after restart.
            for (Bundle b : updateNotRequired.values()) {
                String symbolicName = stripSymbolicName(b.getSymbolicName());
                Version v = b.getVersion();
                Version updateableVersion = new Version(v.getMajor(), v.getMinor(), 0);
                String key = String.format("%s|%s", symbolicName, updateableVersion.toString());
                int startLevel = b.adapt(BundleStartLevel.class).getStartLevel();
                int state = b.getState();
                BundleUpdate update = new BundleUpdate(symbolicName, null, null, v.toString(), history.getLocation(b), startLevel, state);
                if (bundleKeysFromFeatures.contains(key) || coreBundles.containsKey(symbolicName)) {
                    // we don't have to install it separately
                    update.setIndependent(false);
                }
                updatesInThisPatch.add(update);
                updatesForBundleKeys.put(key, update);
            }
        }

        return updatesInThisPatch;
    }

    /**
     * Returns a list of {@link FeatureUpdate} for single patch, taking into account already discovered updates
     * @param patch
     * @param updatesForFeatureKeys
     * @param kind
     * @return
     */
    private List<FeatureUpdate> featureUpdatesInPatch(Patch patch,
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
            Map<String, Repository> before = new HashMap<>(getAvailableFeatureRepositories());
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

            for (Repository existingRepository : before.values()) {
                for (Feature feature : existingRepository.getFeatures()) {
                    Version v = Utils.getFeatureVersion(feature.getVersion());
                    Version lowestUpdateableVersion = new Version(v.getMajor(), v.getMinor(), 0);
                    // assume that we can update feature XXX-2.2.3 to XXX-2.2.142, but not to XXX-2.3.0.alpha-1
                    String key = String.format("%s|%s", feature.getName(), lowestUpdateableVersion.toString());
                    featuresInOldRepositories.put(key, existingRepository.getName());
                    singleFeaturesInOldRepositories.put(feature.getName(), existingRepository.getName());
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
            Set<String> oldRepositoryNames = new HashSet<String>();
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
                            && singleFeaturesInOldRepositories.get(feature.getName()) != null
                            && singleFeaturesInOldRepositories.get(feature.getName()).size() == 1) {
                        oldRepositoryWithUpdateableFeature = singleFeaturesInOldRepositories.get(feature.getName()).get(0);
                    }
                    if (oldRepositoryWithUpdateableFeature != null) {
                        // track the old repository to be removed
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
                        String newRepositoryName = featuresInNewRepositories.get(key);
                        String newVersion = actualNewFeatureVersions.get(key);
                        if (newRepositoryName == null) {
                            // try looking up the feature without version - e.g., like in updating "transaction"
                            // feature from 1.1.1 to 1.3.0
                            if (singleFeaturesInOldRepositories.get(feature.getName()) != null
                                    && singleFeaturesInOldRepositories.get(feature.getName()).size() == 1
                                    && singleFeaturesInNewRepositories.get(feature.getName()) != null
                                    && singleFeaturesInNewRepositories.get(feature.getName()).size() == 1) {
                                newRepositoryName = singleFeaturesInNewRepositories.get(feature.getName()).get(0);
                            }
                        }
                        if (newVersion == null) {
                            if (singleActualNewFeatureVersions.get(feature.getName()) != null
                                    && singleActualNewFeatureVersions.get(feature.getName()).size() == 1) {
                                newVersion = singleActualNewFeatureVersions.get(feature.getName()).get(0);
                            }
                        }
                        FeatureUpdate featureUpdate = null;
                        if (newVersion != null && newRepositoryName != null) {
                            featureUpdate = new FeatureUpdate(feature.getName(),
                                    after.get(oldRepositoryName).getURI().toString(),
                                    feature.getVersion(),
                                    after.get(newRepositoryName).getURI().toString(),
                                    newVersion);
                        } else {
                            // we didn't find an update for installed features among feature repositories from patch
                            // which means we have to preserve both the feature and the repository - this may
                            // be user's feature
                            featureUpdate = new FeatureUpdate(feature.getName(),
                                    after.get(oldRepositoryName).getURI().toString(),
                                    feature.getVersion(),
                                    null,
                                    null);
                        }
                        updatesInThisPatch.add(featureUpdate);
                        // Merge result
                        FeatureUpdate oldUpdate = updatesForFeatureKeys.get(key);
                        if (oldUpdate != null) {
                            Version upv = null, newV = null;
                            if (oldUpdate.getNewVersion() != null) {
                                upv = VersionTable.getVersion(oldUpdate.getNewVersion());
                            }
                            if (newVersion != null) {
                                newV = VersionTable.getVersion(newVersion);
                            }
                            if (upv == null && newV == null) {
                                // weird...
                            } else {
                                if (upv == null || (newV != null && upv.compareTo(newV) < 0)) {
                                    // other patch contains newer update for the feature
                                    updatesForFeatureKeys.put(key, featureUpdate);
                                }
                            }
                        } else {
                            // this is the first update of the bundle
                            updatesForFeatureKeys.put(key, featureUpdate);
                        }
                    }
                }
            }

            // now let's see if there are repositories that are NOT updated (either they're not available in patch
            // (like user feature repositories) or simply didn't change (like jclouds 1.8.1 between Fuse 6.2 and 6.2.1)
            Set<String> unchangedRepositoryNames = new HashSet<>(before.keySet());
            unchangedRepositoryNames.removeAll(oldRepositoryNames);
            for (String unchangedRepositoryName : unchangedRepositoryNames) {
                Repository repository = before.get(unchangedRepositoryName);
                boolean hasInstalledFeatures = false;
                for (Feature feature : repository.getFeatures()) {
                    if (featuresService.isInstalled(feature)) {
                        FeatureUpdate featureUpdate = new FeatureUpdate(feature.getName(),
                                after.get(unchangedRepositoryName).getURI().toString(),
                                feature.getVersion(),
                                null,
                                null);
                        hasInstalledFeatures = true;
                        // preserve unchanged/user feature - install after restart
                        updatesInThisPatch.add(featureUpdate);
                        // the key doesn't matter
                        updatesForFeatureKeys.put(String.format("%s|%s", feature.getName(), feature.getVersion()),
                                featureUpdate);
                    }
                }
                if (!hasInstalledFeatures) {
                    // we have to preserve unchanged/user feature repository - even if it had no installed features
                    // this featureUpdate means - "restore feature repository only"
                    FeatureUpdate featureUpdate = new FeatureUpdate(null,
                            after.get(unchangedRepositoryName).getURI().toString(),
                            null,
                            null,
                            null);
                    updatesInThisPatch.add(featureUpdate);
                    updatesForFeatureKeys.put(String.format("REPOSITORY_TO_ADD:%s", after.get(unchangedRepositoryName).getURI().toString()),
                            featureUpdate);
                }
            }

            return updatesInThisPatch;
        } catch (Exception e) {
            throw new PatchException(e.getMessage(), e);
        } finally {
            // we'll add new feature repositories again later. here we've added them only to track the updates
            if (after != null) {
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
        if (featuresService != null) {
            for (Repository repository : featuresService.listRepositories()) {
                before.put(repository.getName(), repository);
            }
        }
        return before;
    }

    @Override
    public void rollback(final Patch patch, boolean simulate, boolean force) throws PatchException {
        final PatchResult result = patch.getResult();
        if (result == null) {
            throw new PatchException("Patch " + patch.getPatchData().getId() + " is not installed");
        }

        if (patch.getPatchData().isRollupPatch()) {
            // we already have the "state" (feature repositories, features, bundles and their states, datafiles
            // and start-level info) stored in *.result file

            Presentation.displayFeatureUpdates(result.getFeatureUpdates(), false);
            Presentation.displayBundleUpdates(result.getBundleUpdates(), false);

            try {
                if (!simulate) {
                    // let's backup data files before configadmin detects changes to etc/* files.
                    backupService.backupDataFiles(result, Pending.ROLLUP_ROLLBACK);

                    for (Bundle b : this.bundleContext.getBundles()) {
                        if (Utils.stripSymbolicName(b.getSymbolicName()).equals("org.apache.felix.fileinstall")) {
                            b.stop(Bundle.STOP_TRANSIENT);
                            break;
                        }
                    }

                    patchManagement.rollback(patch.getPatchData());
                    result.setPending(Pending.ROLLUP_ROLLBACK);
                    result.store();

                    if (isJvmRestartNeeded(result)) {
                        boolean handlesFullRestart = Boolean.getBoolean("karaf.restart.jvm.supported");
                        if (handlesFullRestart) {
                            System.out.println("Rollup patch " + patch.getPatchData().getId() + " rolled back. Restarting Karaf..");
                            System.setProperty("karaf.restart.jvm", "true");
                        } else {
                            System.out.println("Rollup patch " + patch.getPatchData().getId() + " rolled back. Shutting down Karaf, please restart...");
                        }
                    } else {
                        // We don't need a JVM restart, so lets just do a OSGi framework restart
                        System.setProperty("karaf.restart", "true");
                    }

                    File karafData = new File(bundleContext.getProperty("karaf.data"));
                    File cleanCache = new File(karafData, "clean_cache");
                    cleanCache.createNewFile();
                    bundleContext.getBundle(0l).stop();
                    // stop/shutdown occurs on another thread
                    return;
                } else {
                    System.out.println("Simulation only - no files and runtime data will be modified.");
                    return;
                }
            } catch (Exception e) {
                e.printStackTrace(System.err);
                System.err.flush();
                throw new PatchException(e.getMessage(), e);
            }
        }

        // continue with NON_ROLLUP patch

        // current state of the framework
        Bundle[] allBundles = bundleContext.getBundles();

        // check if all the bundles that were updated in patch are available (installed)
        List<BundleUpdate> badUpdates = new ArrayList<BundleUpdate>();
        for (BundleUpdate update : result.getBundleUpdates()) {
            boolean found = false;
            Version v = Version.parseVersion(update.getNewVersion() == null ? update.getPreviousVersion() : update.getNewVersion());
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
                String version = up.getNewVersion() == null ? up.getPreviousVersion() : up.getNewVersion();
                sb.append(" - ").append(up.getSymbolicName()).append("/").append(version).append("\n");
            }
            throw new PatchException(sb.toString());
        }

        if (!simulate) {
            // bundle -> old location of the bundle to downgrade from
            final Map<Bundle, String> toUpdate = new HashMap<Bundle, String>();
            for (BundleUpdate update : result.getBundleUpdates()) {
                Version v = Version.parseVersion(update.getNewVersion() == null ? update.getPreviousVersion() : update.getNewVersion());
                for (Bundle bundle : allBundles) {
                    if (stripSymbolicName(bundle.getSymbolicName()).equals(stripSymbolicName(update.getSymbolicName()))
                            && bundle.getVersion().equals(v)) {
                        toUpdate.put(bundle, update.getPreviousLocation());
                    }
                }
            }

            patchManagement.rollback(patch.getPatchData());

            Executors.newSingleThreadExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        applyChanges(toUpdate);
                    } catch (Exception e) {
                        throw new PatchException("Unable to rollback patch " + patch.getPatchData().getId() + ": " + e.getMessage(), e);
                    }
                    patch.setResult(null);
                    File file = new File(patchDir, result.getPatchData().getId() + ".patch.result");
                    file.delete();
                }
            });
        }
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
                toRefresh.add(bundle);
            }
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
                        if (update.getNewVersion() != null) {
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
