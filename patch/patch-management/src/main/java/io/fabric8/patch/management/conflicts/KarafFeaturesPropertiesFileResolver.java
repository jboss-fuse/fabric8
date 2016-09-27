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
package io.fabric8.patch.management.conflicts;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.fabric8.patch.management.Artifact;
import io.fabric8.patch.management.Utils;
import org.apache.felix.utils.properties.Properties;
import org.osgi.framework.Version;

public class KarafFeaturesPropertiesFileResolver extends PropertiesFileResolver {

    /**
     * In case of <code>etc/org.apache.karaf.features.cfg</code> we merge at single property level.
     * We assume that both property sources contain a value for given key.
     * @param key
     * @param firstProperties
     * @param secondProperties
     * @param rollback
     * @return
     */
    @Override
    protected String specialPropertyMerge(String key, Properties firstProperties, Properties secondProperties, boolean rollback) {
        if ("featuresBoot".equals(key)) {
            String featuresFromPatch = firstProperties.get(key);
            Set<String> featureNamesFromPatch = new LinkedHashSet<>(Arrays.asList(featuresFromPatch.split("\\s*,\\s*")));
            String currentFeatures = secondProperties.get(key);
            Set<String> currentFeatureNames = new LinkedHashSet<>(Arrays.asList(currentFeatures.split("\\s*,\\s*")));
            featureNamesFromPatch.addAll(currentFeatureNames);
            StringBuilder sw = new StringBuilder();
            for (String fn : featureNamesFromPatch) {
                sw.append(",").append(fn);
            }
            return sw.toString().length() > 0 ? sw.toString().substring(1) : "";
        } else if ("featuresRepositories".equals(key)) {
            // it's a bit tougher here - we have to analyze elements as Maven URIs and compare them by version
            String repositoriesFromPatch = firstProperties.get(key);
            List<String> uriStringsFromPatch = Arrays.asList(repositoriesFromPatch.split("\\s*,\\s*"));
            Map<String, Artifact> urisFromPatch = uris(uriStringsFromPatch);
            String currentRepositories = secondProperties.get(key);
            List<String> currentUriStrings = Arrays.asList(currentRepositories.split("\\s*,\\s*"));
            Map<String, Artifact> currentUris = uris(currentUriStrings);
            StringBuilder sw = new StringBuilder();
            if (!rollback) {
                // during installation - go for newer versions
                for (String ga : urisFromPatch.keySet()) {
                    Artifact a1 = urisFromPatch.get(ga);
                    Artifact a2 = currentUris.get(ga);
                    if (a2 == null) {
                        a2 = a1;
                    }
                    Version v1 = Utils.getOsgiVersion(a1.getVersion());
                    Version v2 = Utils.getOsgiVersion(a2.getVersion());
                    String uri = String.format("mvn:%s/%s/%s/%s/%s",
                            a1.getGroupId(), a1.getArtifactId(),
                            v1.compareTo(v2) < 0 ? v2.toString() : v1.toString(),
                            a1.getType(),
                            a1.getClassifier());
                    sw.append(",").append(uri);
                }
                for (String ga : currentUris.keySet()) {
                    if (!urisFromPatch.containsKey(ga)) {
                        Artifact a2 = currentUris.get(ga);
                        String uri = String.format("mvn:%s/%s/%s/%s/%s",
                                a2.getGroupId(), a2.getArtifactId(),
                                a2.getVersion(),
                                a2.getType(),
                                a2.getClassifier());
                        sw.append(",").append(uri);
                    }
                }
                return sw.toString().length() > 0 ? sw.toString().substring(1) : "";
            } else {
                // during rollback - choose older and watch out for missing repositories
                // take currentUris which is the base we're rolling back to
                for (String ga : currentUris.keySet()) {
                    Artifact a2 = currentUris.get(ga);
                    String uri = String.format("mvn:%s/%s/%s/%s/%s",
                            a2.getGroupId(), a2.getArtifactId(),
                            a2.getVersion(),
                            a2.getType(),
                            a2.getClassifier());
                    sw.append(",").append(uri);
                }
                for (String ga : urisFromPatch.keySet()) {
                    if (!currentUris.containsKey(ga)) {
                        Artifact a1 = urisFromPatch.get(ga);
                        String uri = String.format("mvn:%s/%s/%s/%s/%s",
                                a1.getGroupId(), a1.getArtifactId(),
                                a1.getVersion(),
                                a1.getType(),
                                a1.getClassifier());
                        sw.append(",").append(uri);
                    }
                }
                return sw.toString().length() > 0 ? sw.toString().substring(1) : "";
            }
        }
        return super.specialPropertyMerge(key, firstProperties, secondProperties, rollback);
    }

    /**
     * Returns a map of groupId/artifactId -&gt; artifact
     * @param uriStringsFromPatch
     * @return
     */
    private Map<String,Artifact> uris(List<String> uriStringsFromPatch) {
        Map<String, Artifact> result = new LinkedHashMap<>();
        for (String uri : uriStringsFromPatch) {
            Artifact a = Utils.mvnurlToArtifact(uri, true);
            if (a == null) {
                continue;
            }
            String ga = String.format("%s/%s", a.getGroupId(), a.getArtifactId());
            result.put(ga, a);
        }

        return result;
    }

}
