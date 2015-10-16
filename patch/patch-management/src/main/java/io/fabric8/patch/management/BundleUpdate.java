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

/**
 * Command object to update a bundle with <code>symbolicName</code> from previous version:location to new
 * version:location.
 * If <code>newVersion/newLocation</code> are null, this update means we should reinstall the bundle as is (in rollup
 * patch).
 * If <code>previousVersion/previousLocation</code> are null, this update means we're preparing a future update
 * (for example in fabric mode).
 */
public class BundleUpdate {

    private final String symbolicName;

    private final String previousVersion;
    private final String previousLocation;

    private final String newVersion;
    private String newLocation;

    private int startLevel = -1;
    private int state = -1;

    // if this flag is set, this BundleUpdate is considered as independent update, which means the bundle is
    // updated as part of feature or as part of etc/startup.properties
    private boolean independent = true;

    private String toString;

    public BundleUpdate(String symbolicName, String newVersion, String newLocation, String previousVersion, String previousLocation) {
        this(symbolicName, newVersion, newLocation, previousVersion, previousLocation, -1, -1);
    }

    public BundleUpdate(String symbolicName, String newVersion, String newLocation, String previousVersion, String previousLocation, int startLevel, int state) {
        this.symbolicName = symbolicName;
        this.newVersion = newVersion;
        this.newLocation = newLocation;
        this.previousVersion = previousVersion;
        this.previousLocation = previousLocation;
        this.startLevel = startLevel;
        this.state = state;

        if (symbolicName == null) {
            // for test purposes
            toString = previousLocation;
        } else if (newVersion == null) {
            // reinstallation
            toString = String.format("%s: reinstall from %s (sl=%d)", symbolicName, previousLocation, startLevel);
        } else if (previousVersion == null) {
            // update from any version (fabric mode)
            toString = String.format("%s: update to %s (from %s)", symbolicName, newVersion, newLocation);
        } else {
            // update
            toString = String.format("%s: %s->%s (from %s) (sl=%d)", symbolicName, previousVersion, newVersion, newLocation, startLevel);
        }
    }

    @Override
    public String toString() {
        return toString;
    }

    /**
     * Creates a BundleUpdate with only {@link BundleUpdate#getPreviousLocation()} set.
     * @param oldLocation
     * @return
     */
    public static BundleUpdate from(String oldLocation) {
        return new BundleUpdate(null, null, null, null, oldLocation);
    }

    /**
     * Sets {@link BundleUpdate#getNewLocation()} in a fluent way
     * @param newLocation
     * @return
     */
    public BundleUpdate to(String newLocation) {
        this.newLocation = newLocation;
        return this;
    }

    public String getSymbolicName() {
        return symbolicName;
    }

    public String getNewVersion() {
        return newVersion;
    }

    public String getNewLocation() {
        return newLocation;
    }

    public String getPreviousVersion() {
        return previousVersion;
    }

    public String getPreviousLocation() {
        return previousLocation;
    }

    public int getStartLevel() {
        return startLevel;
    }

    public int getState() {
        return state;
    }

    public boolean isIndependent() {
        return independent;
    }

    public void setIndependent(boolean independent) {
        this.independent = independent;
    }

}
