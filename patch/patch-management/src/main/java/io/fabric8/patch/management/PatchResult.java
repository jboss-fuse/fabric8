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
import java.util.Collection;
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

    private final PatchData patchData;
    private boolean simulation;
    private long date;
    // TODO: the smae for list of updated features
    private Collection<BundleUpdate> updates;

    private String startup;
    private String overrides;

    public PatchResult(PatchData patchData) {
        this.patchData = patchData;
    }

    public PatchResult(PatchData patchData, boolean simulation, long date, Collection<BundleUpdate> updates, String startup,
                       String overrides) {
        this.patchData = patchData;
        this.simulation = simulation;
        this.date = date;
        this.updates = updates;
        this.startup = startup;
        this.overrides = overrides;
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
        List<BundleUpdate> updates = new ArrayList<BundleUpdate>();
        int count = Integer.parseInt(props.getProperty(UPDATES + "." + COUNT, "0"));
        for (int i = 0; i < count; i++) {
            String sn = props.getProperty(UPDATES + "." + Integer.toString(i) + "." + SYMBOLIC_NAME);
            String nv = props.getProperty(UPDATES + "." + Integer.toString(i) + "." + NEW_VERSION);
            String nl = props.getProperty(UPDATES + "." + Integer.toString(i) + "." + NEW_LOCATION);
            String ov = props.getProperty(UPDATES + "." + Integer.toString(i) + "." + OLD_VERSION);
            String ol = props.getProperty(UPDATES + "." + Integer.toString(i) + "." + OLD_LOCATION);
            updates.add(new BundleUpdate(sn, nv, nl, ov, ol));
        }
        String startup = props.getProperty(STARTUP);
        String overrides = props.getProperty(OVERRIDES);

        return new PatchResult(patchData, false, date, updates, startup, overrides);
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
    public void storeTo(OutputStream out) {
        PrintWriter pw = new PrintWriter(out);
        pw.write("# generated file, do not modify\n");
        pw.write("# Installation results for patch \"" + patchData.getId() + "\"\n");

        pw.write(DATE + " = " + Long.toString(getDate()) + "\n");
        pw.write(UPDATES + "." + COUNT + " = " + Integer.toString(getUpdates().size()) + "\n");
        int i = 0;
        for (BundleUpdate update : getUpdates()) {
            pw.write(UPDATES + "." + Integer.toString(i) + "." + SYMBOLIC_NAME + " = " + update.getSymbolicName() + "\n");
            pw.write(UPDATES + "." + Integer.toString(i) + "." + NEW_VERSION + " = " + update.getNewVersion() + "\n");
            pw.write(UPDATES + "." + Integer.toString(i) + "." + NEW_LOCATION + " = " + update.getNewLocation() + "\n");
            pw.write(UPDATES + "." + Integer.toString(i) + "." + OLD_VERSION + " = " + update.getPreviousVersion() + "\n");
            pw.write(UPDATES + "." + Integer.toString(i) + "." + OLD_LOCATION + " = " + update.getPreviousLocation() + "\n");
            i++;
        }
        pw.write(STARTUP + " = " + getStartup() + "\n");
        if (overrides != null) {
            pw.write(OVERRIDES + " = " + overrides + "\n");
        }
        pw.close();
    }

    public boolean isSimulation() {
        return simulation;
    }

    public long getDate() {
        return date;
    }

    public Collection<BundleUpdate> getUpdates() {
        return updates;
    }

    public String getStartup() {
        return startup;
    }

    public String getOverrides() {
        return overrides;
    }

    public PatchData getPatchData() {
        return patchData;
    }

}
