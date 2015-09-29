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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric8.patch.Service;
import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchData;
import io.fabric8.patch.management.PatchException;
import io.fabric8.patch.management.PatchManagement;
import io.fabric8.patch.management.PatchResult;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.utils.manifest.Clause;
import org.apache.felix.utils.manifest.Parser;
import org.apache.felix.utils.version.VersionRange;
import org.apache.felix.utils.version.VersionTable;
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

import static io.fabric8.common.util.IOHelpers.readFully;
import static io.fabric8.common.util.IOHelpers.writeFully;

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

    private static final Pattern SYMBOLIC_NAME_PATTERN = Pattern.compile("([^;: ]+)(.*)");

    private BundleContext bundleContext;
    private File patchDir;

    @Reference(referenceInterface = PatchManagement.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.STATIC)
    private PatchManagement patchManagement;

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
        return load(false).get(id);
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

    private File getPatchStorage(Patch patch) {
        return new File(patchDir, patch.getPatchData().getId());
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

    public void rollback(final Patch patch, boolean force) throws PatchException {
        final PatchResult result = patch.getResult();
        if (result == null) {
            throw new PatchException("Patch " + patch.getPatchData().getId() + " is not installed");
        }
        Bundle[] allBundles = bundleContext.getBundles();
        List<io.fabric8.patch.management.BundleUpdate> badUpdates = new ArrayList<io.fabric8.patch.management.BundleUpdate>();
        for (io.fabric8.patch.management.BundleUpdate update : result.getUpdates()) {
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
            for (io.fabric8.patch.management.BundleUpdate up : badUpdates) {
                sb.append("\t").append(up.getSymbolicName()).append("/").append(up.getNewVersion()).append("\n");
            }
            throw new PatchException(sb.toString());
        }

        final Map<Bundle, String> toUpdate = new HashMap<Bundle, String>();
        for (io.fabric8.patch.management.BundleUpdate update : result.getUpdates()) {
            Version v = Version.parseVersion(update.getNewVersion());
            for (Bundle bundle : allBundles) {
                if (stripSymbolicName(bundle.getSymbolicName()).equals(stripSymbolicName(update.getSymbolicName()))
                        && bundle.getVersion().equals(v)) {
                    toUpdate.put(bundle, update.getPreviousLocation());
                }
            }
        }

        final Offline offline = new Offline(new File(System.getProperty("karaf.base")));
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    applyChanges(toUpdate);
                    writeFully(new File(System.getProperty("karaf.base"), "etc/startup.properties"), ((PatchResult) result).getStartup());
                    writeFully(new File(System.getProperty("karaf.base"), "etc/overrides.properties"), ((PatchResult) result).getOverrides());
                    offline.rollbackPatch(((Patch) patch).getPatchData());
                } catch (Exception e) {
                    throw new PatchException("Unable to rollback patch " + patch.getPatchData().getId() + ": " + e.getMessage(), e);
                }
                ((Patch) patch).setResult(null);
                File file = new File(patchDir, result.getPatchData().getId() + ".patch.result");
                file.delete();
            }
        });
    }

    public PatchResult install(Patch patch, boolean simulate) {
        return install(patch, simulate, true);
    }

    public PatchResult install(Patch patch, boolean simulate, boolean synchronous) {
        Map<String, PatchResult> results = install(Collections.singleton(patch), simulate, synchronous);
        return results.get(patch.getPatchData().getId());
    }

    Map<String, PatchResult> install(final Collection<Patch> patches, boolean simulate, boolean synchronous) {
        checkPrerequisites(patches);
        try {
            // Compute individual patch results
            final Map<String, PatchResult> results = new LinkedHashMap<String, PatchResult>();
            final Map<Bundle, String> toUpdate = new HashMap<Bundle, String>();
            final BundleVersionHistory history = createBundleVersionHistory();
            Map<String, io.fabric8.patch.management.BundleUpdate> allUpdates = new HashMap<String, io.fabric8.patch.management.BundleUpdate>();
            for (Patch patch : patches) {
                String startup = readFully(new File(System.getProperty("karaf.base"), "etc/startup.properties"));
                String overrides = readFully(new File(System.getProperty("karaf.base"), "etc/overrides.properties"));
                List<io.fabric8.patch.management.BundleUpdate> updates = new ArrayList<io.fabric8.patch.management.BundleUpdate>();
                Bundle[] allBundles = bundleContext.getBundles();
                for (String url : patch.getPatchData().getBundles()) {
                    JarInputStream jis = new JarInputStream(new URL(url).openStream());
                    jis.close();
                    Manifest manifest = jis.getManifest();
                    Attributes att = manifest != null ? manifest.getMainAttributes() : null;
                    String sn = att != null ? att.getValue(Constants.BUNDLE_SYMBOLICNAME) : null;
                    String vr = att != null ? att.getValue(Constants.BUNDLE_VERSION) : null;
                    if (sn == null || vr == null) {
                        continue;
                    }
                    Version v = VersionTable.getVersion(vr);

                    VersionRange range = null;

                    if (patch.getPatchData().getVersionRange(url) == null) {
                        // default version range starts with x.y.0 as the lower bound
                        Version lower = new Version(v.getMajor(), v.getMinor(), 0);

                        // We can't really upgrade with versions such as 2.1.0
                        if (v.compareTo(lower) > 0) {
                            range = new VersionRange(false, lower, v, true);
                        }
                    } else {
                        range = new VersionRange(patch.getPatchData().getVersionRange(url));
                    }

                    if (range != null) {
                        for (Bundle bundle : allBundles) {
                            Version oldV = bundle.getVersion();
                            if (bundle.getBundleId() != 0 && stripSymbolicName(sn).equals(stripSymbolicName(bundle.getSymbolicName())) && range.contains(oldV)) {
                                String location = history.getLocation(bundle);
                                io.fabric8.patch.management.BundleUpdate update = new BundleUpdate(sn, v.toString(), url, oldV.toString(), location);
                                updates.add(update);
                                // Merge result
                                io.fabric8.patch.management.BundleUpdate oldUpdate = allUpdates.get(sn);
                                if (oldUpdate != null) {
                                    Version upv = VersionTable.getVersion(oldUpdate.getNewVersion());
                                    if (upv.compareTo(v) < 0) {
                                        allUpdates.put(sn, update);
                                        toUpdate.put(bundle, url);
                                    }
                                } else {
                                    toUpdate.put(bundle, url);
                                }
                            }
                        }
                    } else {
                        System.err.printf("Skipping bundle %s - unable to process bundle without a version range configuration%n", url);
                    }
                }
                if (!simulate) {
                    new Offline(new File(System.getProperty("karaf.base")))
                            .applyConfigChanges(((Patch) patch).getPatchData(), getPatchStorage(patch));
                }
                PatchResult result = new PatchResult(patch.getPatchData(), simulate, System.currentTimeMillis(), updates, startup, overrides);
                results.put(patch.getPatchData().getId(), result);
            }
            // Apply results
            System.out.println("Bundles to update:");
            for (Map.Entry<Bundle, String> e : toUpdate.entrySet()) {
                System.out.println("    " + e.getKey().getSymbolicName() + "/" + e.getKey().getVersion().toString() + " with " + e.getValue());
            }
            if (simulate) {
                System.out.println("Running simulation only - no bundles are being updated at this time");
            } else {
                System.out.println("Installation will begin.  The connection may be lost or the console restarted.");
            }
            System.out.flush();
            if (!simulate) {
                Thread thread = new Thread() {
                    public void run() {
                        try {
                            applyChanges(toUpdate);
                            for (Patch patch : patches) {
                                PatchResult result = results.get(patch.getPatchData().getId());
                                ((Patch) patch).setResult(result);
                                result.store();
                            }
                        } catch (Exception e) {
                            e.printStackTrace(System.err);
                            System.err.flush();
                        }
                    }
                };
                if (synchronous) {
                    thread.run();
                } else {
                    thread.start();
                }
            }
            return results;
        } catch (Exception e) {
            throw new PatchException(e);
        }
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
            BundleUtils.update(bundle, new URL(e.getValue()));
            toRefresh.add(bundle);
            toStart.add(bundle);
        }
        findBundlesWithOptionalPackagesToRefresh(toRefresh);
        findBundlesWithFramentsToRefresh(toRefresh);
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
                bundle.start();
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

    protected void findBundlesWithFramentsToRefresh(Set<Bundle> toRefresh) {
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
     * Strips symbolic name from directives.
     * @param symbolicName
     * @return
     */
    static String stripSymbolicName(String symbolicName) {
        Matcher m = SYMBOLIC_NAME_PATTERN.matcher(symbolicName);
        if (m.matches() && m.groupCount() >= 1) {
            return m.group(1);
        } else {
            return symbolicName;
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
     * Contains the history of bundle versions that have been applied through the patching mechanism
     */
    protected static final class BundleVersionHistory {

        private Map<String, Map<String, String>> bundleVersions = new HashMap<String, Map<String, String>>();

        public BundleVersionHistory(Map<String, Patch> patches) {
            super();
            for (Map.Entry<String, Patch> patch : patches.entrySet()) {
                PatchResult result = patch.getValue().getResult();
                if (result != null) {
                    for (io.fabric8.patch.management.BundleUpdate update : result.getUpdates()) {
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
