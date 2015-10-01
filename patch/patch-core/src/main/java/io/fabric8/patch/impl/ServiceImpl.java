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
import java.io.IOException;
import java.net.URI;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import io.fabric8.patch.Service;
import io.fabric8.patch.management.Artifact;
import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchData;
import io.fabric8.patch.management.PatchDetailsRequest;
import io.fabric8.patch.management.PatchException;
import io.fabric8.patch.management.PatchKind;
import io.fabric8.patch.management.PatchManagement;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.patch.management.Utils;
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

import static io.fabric8.common.util.Files.copy;
import static io.fabric8.common.util.IOHelpers.readFully;
import static io.fabric8.patch.management.Utils.mvnurlToArtifact;
import static io.fabric8.patch.management.Utils.stripSymbolicName;

@Component(immediate = true, metatype = false)
@org.apache.felix.scr.annotations.Service(Service.class)
public class ServiceImpl implements Service {

    HashSet<String> SPECIAL_BUNDLE_SYMBOLIC_NAMES = new HashSet<>(Arrays.asList(
            "org.ops4j.pax.url.mvn",
            "org.ops4j.pax.logging.pax-logging-api",
            "org.ops4j.pax.logging.pax-logging-service"
            ));

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
     * Main installation method. Installing a patch in non-fabric mode is a matter of merging patch branch to
     * <code>master</code> branch.
     * @param patches
     * @param simulate
     * @param synchronous
     * @return
     */
    private Map<String, PatchResult> install(final Collection<Patch> patches, boolean simulate, boolean synchronous) {
        PatchKind kind = checkConsistency(patches);
        checkPrerequisites(patches);
        String transaction = null;
        try {
            // Compute individual patch results (patchId -> Result)
            final Map<String, PatchResult> results = new LinkedHashMap<String, PatchResult>();

            // current state of the framework
            Bundle[] allBundles = bundleContext.getBundles();

            // symbolic name -> newest update for the bundle out of all installedpatches
            Map<String, BundleUpdate> allUpdates = new HashMap<String, BundleUpdate>();
            // bundle -> url to update the bundle from
            final Map<Bundle, String> toUpdate = new HashMap<Bundle, String>();
            // symbolic name -> version -> location
            final BundleVersionHistory history = createBundleVersionHistory();

            transaction = this.patchManagement.beginInstallation(kind);

            for (Patch patch : patches) {
                String startup = readFully(new File(System.getProperty("karaf.base"), "etc/startup.properties"));
                String overrides = readFully(new File(System.getProperty("karaf.base"), "etc/overrides.properties"));
                // list of bundle updates for the current patch
                List<BundleUpdate> updates = new ArrayList<BundleUpdate>();

                for (String url : patch.getPatchData().getBundles()) {
                    // [symbolicName, version] of the new bundle
                    String[] symbolicNameVersion = getBundleIdentity(url);
                    if (symbolicNameVersion == null) {
                        continue;
                    }
                    String sn = symbolicNameVersion[0];
                    String vr = symbolicNameVersion[1];
                    Version newV = VersionTable.getVersion(vr);

                    // if existing bundle is withing this range, update is possible
                    VersionRange range = getUpdateableRange(patch, url, newV);

                    if (range != null) {
                        for (Bundle bundle : allBundles) {
                            if (bundle.getBundleId() == 0L) {
                                continue;
                            }
                            if (!stripSymbolicName(sn).equals(stripSymbolicName(bundle.getSymbolicName()))) {
                                continue;
                            }
                            Version oldV = bundle.getVersion();
                            if (range.contains(oldV)) {
                                String location = history.getLocation(bundle);
                                BundleUpdate update = new BundleUpdate(sn, newV.toString(), url, oldV.toString(), location);
                                updates.add(update);
                                // Merge result
                                BundleUpdate oldUpdate = allUpdates.get(sn);
                                if (oldUpdate != null) {
                                    Version upv = VersionTable.getVersion(oldUpdate.getNewVersion());
                                    if (upv.compareTo(newV) < 0) {
                                        // other patch contains newer update for a bundle
                                        allUpdates.put(sn, update);
                                        toUpdate.put(bundle, url);
                                    }
                                } else {
                                    // this is the first update of the bundle
                                    toUpdate.put(bundle, url);
                                }
                            }
                        }
                    } else {
                        if (kind == PatchKind.ROLLUP) {
                            // we simply do not touch the bundle from patch - it is installed in KARAF_HOME/system
                            // and available to be installed when new features are detected
                        } else {
                            // for non-rollup patches however, we signal an error - all the bundles from P patch
                            // should be used to update already installed bundles
                            System.err.printf("Skipping bundle %s - unable to process bundle without a version range configuration%n", url);
                        }
                    }
                }

                // each patch may change files, we're not updating the main files yet - it'll be done when
                // install transaction is committed
                patchManagement.install(transaction, patch);

                if (patch.getPatchData().getMigratorBundle() != null) {
                    Artifact artifact = mvnurlToArtifact(patch.getPatchData().getMigratorBundle(), true);
                    if (artifact != null) {
                        // Copy it to the deploy dir
                        File repo = new File(bundleContext.getBundle(0).getBundleContext().getProperty("karaf.default.repository"));
                        File karafHome = new File(bundleContext.getBundle(0).getBundleContext().getProperty("karaf.home"));
                        File src = new File(repo, artifact.getPath());
                        File target = new File(Utils.getDeployDir(karafHome), artifact.getArtifactId() + ".jar");
                        copy(src, target);
                    }
                }

                // prepare patch result before doing runtime changes
                PatchResult result = new PatchResult(patch.getPatchData(), simulate, System.currentTimeMillis(), updates, startup, overrides);
                results.put(patch.getPatchData().getId(), result);
            }

            // Remove bundles from the udpate list where an update is not needed or
            // would break our patching bundle.
            for (Map.Entry<Bundle, String> entry : new HashSet<>(toUpdate.entrySet())) {
                Bundle bundle = entry.getKey();
                if( SPECIAL_BUNDLE_SYMBOLIC_NAMES.contains(bundle.getSymbolicName()) ) {
                    System.out.println("Skipping bundle update of: "+bundle);
                    toUpdate.remove(bundle);
                } else if( bundle.getLocation().equals(entry.getValue()) ) {
                    System.out.println("Bundle is up to date: "+bundle);
                    toUpdate.remove(bundle);
                }
            }

            // Apply results

            System.out.println("Bundles to update:");
            for (Map.Entry<Bundle, String> e : toUpdate.entrySet()) {
                System.out.println("    " + e.getKey().getSymbolicName() + "/" + e.getKey().getVersion().toString() + " with " + e.getValue());
            }
            if (simulate) {
                System.out.println("Running simulation only - no bundles are being updated at this time");
            } else {
                System.out.println("Installation will begin. The connection may be lost or the console restarted.");
            }
            System.out.flush();

            if (!simulate) {
                Runnable task = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            applyChanges(toUpdate);
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
            }

            if (!simulate) {
                patchManagement.commitInstallation(transaction);
            } else {
                patchManagement.rollbackInstallation(transaction);
            }

            if (featuresService != null) {
                // TODO: it may be null in tests
                for (Patch patch : patches) {
                    installFeatures(patch, simulate);
                }
            }

            return results;
        } catch (Exception e) {
            if (transaction != null) {
                patchManagement.rollbackInstallation(transaction);
            }
            throw new PatchException(e.getMessage(), e);
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

    private void installFeatures(Patch patch, boolean simulate) throws Exception {

        // install the new feature repos, tracking the set the were
        // installed before and after
        HashMap<String, Repository> before = getFeatureRepos();
        for (String url : patch.getPatchData().getFeatureFiles() ) {
            featuresService.addRepository(new URI(url));
        }
        HashMap<String, Repository> after = getFeatureRepos();

        // track which repos provide which features..
        MultiMap<String, String> features = new MultiMap<String, String>();
        for (Repository repository : before.values()) {
            for (Feature feature : repository.getFeatures()) {
                features.put(feature.getName(), repository.getName());
            }
        }

        // Use the before and after set to figure out which repos were added.
        Set<String> addedKeys = new HashSet<>(after.keySet());
        addedKeys.removeAll(before.keySet());

        // Figure out which old repos were updated:  Do they have feature
        // with the same name as one contained in a repo being added?
        HashSet<String> oldRepos = new HashSet<String>();
        for (String key : addedKeys) {
            Repository added = after.get(key);
            for (Feature feature : added.getFeatures()) {
                List<String> repos = features.get(feature.getName());
                if( repos!=null ) {
                    oldRepos.addAll(repos);
                }
            }
        }

        // Now we know which are the old repos that have been udpated.
        // We need to uninstall them.  Before we uninstall, track which features
        // were installed.
        HashSet<String> featuresToInstall = new HashSet<String>();
        for (String repoName : oldRepos) {
            Repository repository = before.get(repoName);
            for (Feature feature : repository.getFeatures()) {
                if( featuresService.isInstalled(feature) ) {
                    featuresToInstall.add(feature.getName());
                }
            }
            if (simulate) {
                System.out.println("Simulation: Remove feature repository: "+repository.getURI());
            } else {
                System.out.println("Remove feature repository: "+repository.getURI());
                featuresService.removeRepository(repository.getURI(), true);
            }
        }

        // Now that we don't have any of the old feature repos installed
        // Lets re-install the features that were previously installed.
        for (String f : featuresToInstall) {
            if (simulate) {
                System.out.println("Simulation: Enable feature: "+f);
            } else {
                System.out.println("Enable feature: "+f);
                featuresService.installFeature(f);
            }
        }

        if( simulate ) {
            // Undo the add we had done.
            for (String url : patch.getPatchData().getFeatureFiles() ) {
                featuresService.removeRepository(new URI(url));
            }
        }

    }

    private HashMap<String, Repository> getFeatureRepos() {
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
        for (BundleUpdate update : result.getUpdates()) {
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
        for (BundleUpdate update : result.getUpdates()) {
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
        toStop.addAll(toUpdate.keySet());
        while (!toStop.isEmpty()) {
            List<Bundle> bs = getBundlesToDestroy(toStop);
            for (Bundle bundle : bs) {
                String hostHeader = (String) bundle.getHeaders().get(Constants.FRAGMENT_HOST);
                if (hostHeader == null && (bundle.getState() == Bundle.ACTIVE || bundle.getState() == Bundle.STARTING)) {
                    bundle.stop();
                }
                toStop.remove(bundle);
            }
        }
        Set<Bundle> toRefresh = new HashSet<Bundle>();
        Set<Bundle> toStart = new HashSet<Bundle>();
        for (Map.Entry<Bundle, String> e : toUpdate.entrySet()) {
            Bundle bundle = e.getKey();
            System.out.println("updating: "+bundle.getSymbolicName());
            try {
                BundleUtils.update(bundle, new URL(e.getValue()));
            } catch (BundleException ex) {
                System.err.println("Failed to update: " + bundle.getSymbolicName()+", due to: "+e);
            }
            toRefresh.add(bundle);
            toStart.add(bundle);
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
                    for (BundleUpdate update : result.getUpdates()) {
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
