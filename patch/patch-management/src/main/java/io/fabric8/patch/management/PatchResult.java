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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.commons.io.IOUtils;

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

    private static final String FEATURE_UPDATES = "feature-update";
    private static final String FEATURE_NAME = "name";
    private static final String FEATURE_NEW_REPOSITORY = "new-repository";
    private static final String FEATURE_OLD_REPOSITORY = "old-repository";

    private static final String COUNT = "count";
    private static final String RANGE = "range";

    private final PatchData patchData;
    private boolean simulation;
    private long date;

    private List<BundleUpdate> bundleUpdates;
    private List<FeatureUpdate> featureUpdates;

    public PatchResult(PatchData patchData) {
        this.patchData = patchData;
    }

    public PatchResult(PatchData patchData, boolean simulation, long date,
                       List<BundleUpdate> bundleUpdates, List<FeatureUpdate> featureUpdates) {
        this.patchData = patchData;
        this.simulation = simulation;
        this.date = date;
        this.bundleUpdates = bundleUpdates;
        this.featureUpdates = featureUpdates;
    }

    /**
     * Static constructor of PatchResult object that takes initialization data from {@link java.io.InputStream}.
     * @param patchData patch data this result relates to
     * @param inputStream
     * @return
     * @throws IOException
     */
    public static PatchResult load(PatchData patchData, InputStream inputStream) throws IOException {
        Properties props = new Properties();
        props.load(inputStream);
        IOUtils.closeQuietly(inputStream);
        return load(patchData, props);
    }

    /**
     * Static constructor of PatchResult object that takes initialization data from {@link Properties} file.
     * @param patchData
     * @param props
     * @return
     * @throws IOException
     */
    public static PatchResult load(PatchData patchData, Properties props) {
        long date = Long.parseLong(props.getProperty(DATE));

        List<BundleUpdate> bupdates = new ArrayList<>();
        int count = Integer.parseInt(props.getProperty(BUNDLE_UPDATES + "." + COUNT, "0"));
        for (int i = 0; i < count; i++) {
            String sn = props.getProperty(BUNDLE_UPDATES + "." + Integer.toString(i) + "." + SYMBOLIC_NAME);
            String nv = props.getProperty(BUNDLE_UPDATES + "." + Integer.toString(i) + "." + NEW_VERSION);
            String nl = props.getProperty(BUNDLE_UPDATES + "." + Integer.toString(i) + "." + NEW_LOCATION);
            String ov = props.getProperty(BUNDLE_UPDATES + "." + Integer.toString(i) + "." + OLD_VERSION);
            String ol = props.getProperty(BUNDLE_UPDATES + "." + Integer.toString(i) + "." + OLD_LOCATION);
            bupdates.add(new BundleUpdate(sn, nv, nl, ov, ol));
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

        return new PatchResult(patchData, false, date, bupdates, fupdates);
    }

    public void store() throws IOException {
        FileOutputStream fos = new FileOutputStream(new File(patchData.getPatchLocation(),
                patchData.getId() + ".patch.result"));
        storeTo(fos);
        fos.close();
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
            pw.write(BUNDLE_UPDATES + "." + Integer.toString(i) + "." + SYMBOLIC_NAME + " = " + update.getSymbolicName() + "\n");
            pw.write(BUNDLE_UPDATES + "." + Integer.toString(i) + "." + NEW_VERSION + " = " + update.getNewVersion() + "\n");
            pw.write(BUNDLE_UPDATES + "." + Integer.toString(i) + "." + NEW_LOCATION + " = " + update.getNewLocation() + "\n");
            pw.write(BUNDLE_UPDATES + "." + Integer.toString(i) + "." + OLD_VERSION + " = " + update.getPreviousVersion() + "\n");
            pw.write(BUNDLE_UPDATES + "." + Integer.toString(i) + "." + OLD_LOCATION + " = " + update.getPreviousLocation() + "\n");
            i++;
        }

        pw.write(FEATURE_UPDATES + "." + COUNT + " = " + Integer.toString(getFeatureUpdates().size()) + "\n");
        i = 0;
        for (FeatureUpdate update : getFeatureUpdates()) {
            pw.write(FEATURE_UPDATES + "." + Integer.toString(i) + "." + FEATURE_NAME + " = " + update.getName() + "\n");
            pw.write(FEATURE_UPDATES + "." + Integer.toString(i) + "." + FEATURE_NEW_REPOSITORY + " = " + update.getNewRepository() + "\n");
            pw.write(FEATURE_UPDATES + "." + Integer.toString(i) + "." + NEW_VERSION + " = " + update.getNewVersion() + "\n");
            pw.write(FEATURE_UPDATES + "." + Integer.toString(i) + "." + FEATURE_OLD_REPOSITORY + " = " + update.getPreviousRepository() + "\n");
            pw.write(FEATURE_UPDATES + "." + Integer.toString(i) + "." + OLD_VERSION + " = " + update.getPreviousVersion() + "\n");
            i++;
        }

        pw.close();
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

}
