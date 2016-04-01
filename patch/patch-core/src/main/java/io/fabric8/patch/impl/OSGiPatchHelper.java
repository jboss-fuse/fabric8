/**
 *  Copyright 2005-2016 Red Hat, Inc.
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
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import io.fabric8.patch.management.FeatureUpdate;
import io.fabric8.patch.management.Utils;
import org.apache.commons.io.IOUtils;
import org.apache.karaf.features.FeaturesService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;

public class OSGiPatchHelper {

    private File karafHome;
    private BundleContext bundleContext;

    public OSGiPatchHelper(File karafHome, BundleContext bundleContext) {
        this.karafHome = karafHome;
        this.bundleContext = bundleContext;
    }

    /**
     * Returns a map of bundles (symbolic name -> Bundle) that were installed in <em>classic way</em> - i.e.,
     * not using {@link FeaturesService}.
     * User may have installed other bundles, drop some to <code>deploy/</code>, etc, but these probably
     * are not handled by patch mechanism.
     * @param allBundles
     * @return
     */
    public Map<String, Bundle> getCoreBundles(Bundle[] allBundles) throws IOException {
        Map<String, Bundle> coreBundles = new HashMap<>();

        Properties props = new Properties();
        FileInputStream stream = new FileInputStream(new File(karafHome, "etc/startup.properties"));
        props.load(stream);
        Set<String> locations = new HashSet<>();
        for (String startupBundle : props.stringPropertyNames()) {
            locations.add(Utils.pathToMvnurl(startupBundle));
        }
        for (Bundle b : allBundles) {
            if (b == null || b.getSymbolicName() == null) {
                continue;
            }
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

    /**
     * Returns two element table: symbolic name and version
     * @param url
     * @return
     * @throws IOException
     */
    public String[] getBundleIdentity(String url) throws IOException {
        // let's try to convert mvn: url to file:
        URL loc = new URL(url);
        String newUrl = Utils.mvnurlToPath(url);
        File repoLocation = new File(Utils.getSystemRepository(karafHome, bundleContext), newUrl);
        if (repoLocation.isFile()) {
            loc = repoLocation.toURI().toURL();
        }
        JarInputStream jis = new JarInputStream(loc.openStream());
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
     * Sorts feature updates in the way that features listed in etc/org.apache.karaf.features.cfg:featuresBoot
     * are updated first
     * @param featureUpdatesInThisPatch
     */
    public void sortFeatureUpdates(List<FeatureUpdate> featureUpdatesInThisPatch) throws IOException {
        Properties props = new Properties();
        FileInputStream stream = new FileInputStream(new File(karafHome, "etc/org.apache.karaf.features.cfg"));
        props.load(stream);
        IOUtils.closeQuietly(stream);
        String p = props.getProperty("featuresBoot");
        if (p != null) {
            String[] properties = p.split("\\s,\\s");
            Set<String> featuresBoot = new LinkedHashSet<>(Arrays.asList(properties));
//            Set<String> featuresSpecial = new LinkedHashSet<>(Arrays.asList("cxf-specs", "cxf-core", "cxf-jaxrs"));
            Set<String> featuresSpecial = new LinkedHashSet<>(Arrays.asList("fabric-cxf"));
            Map<String, FeatureUpdate> newOrderedUpdates = new HashMap<>();
            List<FeatureUpdate> newOtherUpdates = new LinkedList<>();
            List<FeatureUpdate> newUpdates = new LinkedList<>();
            for (Iterator<FeatureUpdate> iterator = featureUpdatesInThisPatch.iterator(); iterator.hasNext(); ) {
                FeatureUpdate update = iterator.next();
                if (update.getName() != null && (featuresBoot.contains(update.getName()) || featuresSpecial.contains(update.getName()))) {
                    newOrderedUpdates.put(update.getName(), update);
                } else {
                    newOtherUpdates.add(update);
                }
            }
            for (String fb : featuresBoot) {
                if (newOrderedUpdates.containsKey(fb)) {
                    newUpdates.add(newOrderedUpdates.get(fb));
                }
            }
            for (String fs : featuresSpecial) {
                if (newOrderedUpdates.containsKey(fs)) {
                    newUpdates.add(newOrderedUpdates.get(fs));
                }
            }
            newUpdates.addAll(newOtherUpdates);
            featureUpdatesInThisPatch.clear();
            featureUpdatesInThisPatch.addAll(newUpdates);
        }
    }
}
