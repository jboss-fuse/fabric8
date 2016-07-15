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
package io.fabric8.patch.management;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.fabric8.patch.management.impl.Activator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.ParseException;
import org.osgi.service.log.LogService;

/**
 * Information about installed patch. This information is generated and stored to <code>*.patch.result</code> file
 * after installing a patch.
 */
public class PatchResult {

    private static final String ID = "id";
    private static final String DESCRIPTION = "description";
    private static final String DATE = "date";
    private static final String BUNDLES = "bundle";

    private static final String BUNDLE_UPDATES = "update";
    private static final String SYMBOLIC_NAME = "symbolic-name";
    private static final String NEW_VERSION = "new-version";
    private static final String NEW_LOCATION = "new-location";
    private static final String OLD_VERSION = "old-version";
    private static final String OLD_LOCATION = "old-location";
    private static final String STATE = "state";
    private static final String START_LEVEL = "start-level";
    private static final String INDEPENDENT = "independent";

    private static final String FEATURE_UPDATES = "feature-update";
    private static final String FEATURE_NAME = "name";
    private static final String FEATURE_NEW_REPOSITORY = "new-repository";
    private static final String FEATURE_OLD_REPOSITORY = "old-repository";

    private static final String VERSIONS = "version";

    private static final String KARAF_BASE = "base";

    private static final String COUNT = "count";
    private static final String RANGE = "range";

    private final PatchData patchData;
    private boolean simulation;
    private long date;

    // whether this result is not ready yet - there are some tasks left to be done after restart
    private Pending pending = null;

    private List<BundleUpdate> bundleUpdates = new LinkedList<>();
    private List<FeatureUpdate> featureUpdates = new LinkedList<>();
    private List<String> versions = new LinkedList<>();
    private List<String> karafBases = new LinkedList<>();

    // transient
    private Map<String, PatchResult> childPatches = new HashMap<>();
    private PatchResult parent;

    public PatchResult(PatchData patchData) {
        this.patchData = patchData;
    }

    public PatchResult(PatchData patchData, boolean simulation, long date,
                       List<BundleUpdate> bundleUpdates, List<FeatureUpdate> featureUpdates) {
        this(patchData, simulation, date, bundleUpdates, featureUpdates, null);
    }

    public PatchResult(PatchData patchData, boolean simulation, long date,
                       List<BundleUpdate> bundleUpdates, List<FeatureUpdate> featureUpdates,
                       PatchResult parent) {
        this.patchData = patchData;
        this.simulation = simulation;
        this.date = date;
        if (bundleUpdates != null) {
            this.bundleUpdates.addAll(bundleUpdates);
        }
        if (featureUpdates != null) {
            this.featureUpdates.addAll(featureUpdates);
        }
        this.parent = parent;
    }

    /**
     * Static constructor of PatchResult object that takes initialization data from {@link java.io.InputStream}.
     * {@link InputStream} is closed after reading.
     * @param patchData patch data this result relates to
     * @param inputStream
     * @return
     * @throws IOException
     */
    public static PatchResult load(PatchData patchData, InputStream inputStream) throws IOException {
        return load(patchData, inputStream, true);
    }

    /**
     * Static constructor of PatchResult object that takes initialization data from {@link java.io.InputStream}.
     * {@link InputStream} is closed after reading.
     * @param patchData patch data this result relates to
     * @param inputStream
     * @param processChildren
     * @return
     * @throws IOException
     */
    public static PatchResult load(PatchData patchData, InputStream inputStream, boolean processChildren) throws IOException {
        Properties props = new Properties();
        props.load(inputStream);
        IOUtils.closeQuietly(inputStream);
        return load(patchData, props, processChildren);
    }

    /**
     * Static constructor of PatchResult object that takes initialization data from {@link Properties} file.
     * @param patchData
     * @param props
     * @return
     * @throws IOException
     */
    public static PatchResult load(PatchData patchData, Properties props) {
        return load(patchData, props, true);
    }

    /**
     * Static constructor of PatchResult object that takes initialization data from {@link Properties} file.
     * @param patchData
     * @param props
     * @param processChildren
     * @return
     * @throws IOException
     */
    public static PatchResult load(final PatchData patchData, Properties props, final boolean processChildren) {
        long date = Long.parseLong(props.getProperty(DATE));

        List<BundleUpdate> bupdates = new ArrayList<>();
        int count = Integer.parseInt(props.getProperty(BUNDLE_UPDATES + "." + COUNT, "0"));
        for (int i = 0; i < count; i++) {
            String prefix = BUNDLE_UPDATES + "." + Integer.toString(i) + ".";
            String sn = props.getProperty(prefix + SYMBOLIC_NAME);
            String nv = props.getProperty(prefix + NEW_VERSION);
            String nl = props.getProperty(prefix + NEW_LOCATION);
            String ov = props.getProperty(prefix + OLD_VERSION);
            String ol = props.getProperty(prefix + OLD_LOCATION);
            int state = -1, startLevel = -1;
            String _state = props.getProperty(prefix + STATE);
            if (_state != null && !"".equals(_state)) {
                try {
                    state = Integer.parseInt(_state);
                } catch (ParseException ignored) {
                }
            }
            String _startLevel = props.getProperty(prefix + START_LEVEL);
            if (_startLevel != null && !"".equals(_startLevel)) {
                try {
                    startLevel = Integer.parseInt(_startLevel);
                } catch (ParseException ignored) {
                }
            }
            BundleUpdate bundleUpdate = new BundleUpdate(sn, nv, nl, ov, ol, startLevel, state);
            if (props.getProperty(prefix + INDEPENDENT) != null) {
                bundleUpdate.setIndependent(Boolean.parseBoolean(props.getProperty(prefix + INDEPENDENT)));
            }
            bupdates.add(bundleUpdate);
        }

        List<FeatureUpdate> fupdates = new ArrayList<>();
        count = Integer.parseInt(props.getProperty(FEATURE_UPDATES + "." + COUNT, "0"));
        for (int i = 0; i < count; i++) {
            String n = props.getProperty(FEATURE_UPDATES + "." + Integer.toString(i) + "." + FEATURE_NAME);
            String nr = props.getProperty(FEATURE_UPDATES + "." + Integer.toString(i) + "." + FEATURE_NEW_REPOSITORY);
            String nv = props.getProperty(FEATURE_UPDATES + "." + Integer.toString(i) + "." + NEW_VERSION);
            String or = props.getProperty(FEATURE_UPDATES + "." + Integer.toString(i) + "." + FEATURE_OLD_REPOSITORY);
            String ov = props.getProperty(FEATURE_UPDATES + "." + Integer.toString(i) + "." + OLD_VERSION);
            fupdates.add(new FeatureUpdate(n, or, ov, nr, nv));
        }

        List<String> versions = new ArrayList<>();
        count = Integer.parseInt(props.getProperty(VERSIONS + "." + COUNT, "0"));
        for (int i = 0; i < count; i++) {
            versions.add(props.getProperty(VERSIONS + "." + Integer.toString(i)));
        }

        List<String> karafBases = new ArrayList<>();
        count = Integer.parseInt(props.getProperty(KARAF_BASE + "." + COUNT, "0"));
        for (int i = 0; i < count; i++) {
            karafBases.add(props.getProperty(KARAF_BASE + "." + Integer.toString(i)));
        }

        final PatchResult result = new PatchResult(patchData, false, date, bupdates, fupdates);
        result.getVersions().addAll(versions);
        result.getKarafBases().addAll(karafBases);

        // load child patch results
        if (processChildren) {
            patchData.getPatchLocation().listFiles(new FileFilter() {
                @Override
                public boolean accept(File pathname) {
                    if (!pathname.isFile()) {
                        return false;
                    }
                    String prefix = patchData.getId() + ".";
                    String suffix = ".patch.result";
                    if (pathname.getName().equals(patchData.getId() + suffix)) {
                        // it's a result from root container
                        return false;
                    }
                    if (!pathname.getName().startsWith(prefix) || !pathname.getName().endsWith(suffix)) {
                        // it's not correct patch result
                        return false;
                    }
                    String childId = pathname.getName().substring(0, pathname.getName().length() - suffix.length());
                    childId = childId.substring(prefix.length());
                    try {
                        result.addChildResult(childId, load(patchData, new FileInputStream(pathname), false));
                    } catch (IOException e) {
                        Activator.log2(LogService.LOG_WARNING, "Can't load patch result from \"" + pathname + "\"");
                    }
                    return false;
                }
            });
        }

        return result;
    }

    public void store() throws IOException {
        FileOutputStream fos = new FileOutputStream(new File(patchData.getPatchLocation(),
                patchData.getId() + ".patch.result"));
        storeTo(fos);
        fos.close();
        if (pending != null) {
            FileUtils.write(new File(patchData.getPatchLocation(), patchData.getId() + ".patch.pending"), pending.toString());
        }

        for (String child : childPatches.keySet()) {
            PatchResult pr = childPatches.get(child);
            fos = new FileOutputStream(new File(patchData.getPatchLocation(),
                    patchData.getId() + "." + child + ".patch.result"));
            pr.storeTo(fos);
            fos.close();
            if (pr.pending != null) {
                FileUtils.write(new File(patchData.getPatchLocation(), patchData.getId() + "." + child + ".patch.pending"), pr.pending.toString());
            }
        }
    }

    /**
     * Persist the result. The <code>out</code> {@link OutputStream} is closed after the write.
     * @param out
     */
    public void storeTo(OutputStream out) throws IOException {
        PrintWriter pw = new PrintWriter(out);
        pw.write("# generated file, do not modify\n");
        pw.write("# Installation results for patch \"" + patchData.getId() + "\"\n");

        pw.write(DATE + " = " + Long.toString(getDate()) + "\n");

        pw.write(BUNDLE_UPDATES + "." + COUNT + " = " + Integer.toString(getBundleUpdates().size()) + "\n");
        int i = 0;
        for (BundleUpdate update : getBundleUpdates()) {
            String prefix = BUNDLE_UPDATES + "." + Integer.toString(i) + ".";
            pw.write(prefix + SYMBOLIC_NAME + " = " + update.getSymbolicName() + "\n");
            pw.write(prefix + OLD_VERSION + " = " + update.getPreviousVersion() + "\n");
            pw.write(prefix + OLD_LOCATION + " = " + update.getPreviousLocation().replace("\\", "\\\\") + "\n");
            pw.write(prefix + INDEPENDENT + " = " + update.isIndependent() + "\n");
            if (update.getNewVersion() != null) {
                pw.write(prefix + NEW_VERSION + " = " + update.getNewVersion() + "\n");
                pw.write(prefix + NEW_LOCATION + " = " + update.getNewLocation().replace("\\", "\\\\") + "\n");
            }
            if (update.getStartLevel() > -1) {
                pw.write(prefix + START_LEVEL + " = " + update.getStartLevel() + "\n");
            }
            if (update.getState() > -1) {
                pw.write(prefix + STATE + " = " + update.getState() + "\n");
            }
            i++;
        }

        pw.write(FEATURE_UPDATES + "." + COUNT + " = " + Integer.toString(getFeatureUpdates().size()) + "\n");
        i = 0;
        for (FeatureUpdate update : getFeatureUpdates()) {
            if (update.getName() != null) {
                pw.write(FEATURE_UPDATES + "." + Integer.toString(i) + "." + FEATURE_NAME + " = " + update.getName() + "\n");
            }
            if (update.getNewVersion() != null) {
                pw.write(FEATURE_UPDATES + "." + Integer.toString(i) + "." + NEW_VERSION + " = " + update.getNewVersion() + "\n");
                pw.write(FEATURE_UPDATES + "." + Integer.toString(i) + "." + FEATURE_NEW_REPOSITORY + " = " + update.getNewRepository() + "\n");
            }
            if (update.getPreviousVersion() != null) {
                pw.write(FEATURE_UPDATES + "." + Integer.toString(i) + "." + OLD_VERSION + " = " + update.getPreviousVersion() + "\n");
            }
            pw.write(FEATURE_UPDATES + "." + Integer.toString(i) + "." + FEATURE_OLD_REPOSITORY + " = " + update.getPreviousRepository() + "\n");
            i++;
        }

        pw.write(VERSIONS + "." + COUNT + " = " + Integer.toString(getVersions().size()) + "\n");
        i = 0;
        for (String version : getVersions()) {
            pw.write(VERSIONS + "." + Integer.toString(i) + " = " + version + "\n");
            i++;
        }

        pw.write(KARAF_BASE + "." + COUNT + " = " + Integer.toString(getKarafBases().size()) + "\n");
        i = 0;
        for (String base : getKarafBases()) {
            pw.write(KARAF_BASE + "." + Integer.toString(i) + " = " + base + "\n");
            i++;
        }

        pw.close();
    }

    /**
     * Each patch in standalone mode may be installed for root container and <code>admin:create</code>-based
     * child containers.
     * @param instance
     * @param childResult
     */
    public void addChildResult(String instance, PatchResult childResult) {
        childPatches.put(instance, childResult);
    }

    public Map<String, PatchResult> getChildPatches() {
        return childPatches;
    }

    public PatchResult getParent() {
        return parent;
    }

    public boolean isSimulation() {
        return simulation;
    }

    public long getDate() {
        return date;
    }

    public List<BundleUpdate> getBundleUpdates() {
        return bundleUpdates;
    }

    public List<FeatureUpdate> getFeatureUpdates() {
        return featureUpdates;
    }

    public PatchData getPatchData() {
        return patchData;
    }

    public Pending isPending() {
        return pending;
    }

    public void setPending(Pending pending) {
        this.pending = pending;
    }

    public List<String> getVersions() {
        return versions;
    }

    public List<String> getKarafBases() {
        return karafBases;
    }

}
