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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import io.fabric8.patch.management.impl.Activator;
import org.apache.felix.utils.properties.Properties;
import org.osgi.service.log.LogService;

/**
 * <p>A kind of 3-way merge conflict resolver, but working at property name/value level.</p>
 * <p>We collect name-value pairs and if value isn't changed either between firstChange vs. base or between
 * secondChange vs. base, there's no conflict. If both diffs change property value, we choose value from
 * <code>secondChange</code>.</p>
 */
public class PropertiesFileResolver implements ResolverEx {

    @Override
    public String resolve(File firstChange, File base, File secondChange) {
        return resolve(firstChange, base, secondChange, true, false);
    }

    @Override
    public String resolve(File firstChange, File base, File secondChange, boolean useFirstChangeAsBase, boolean rollback/*=false*/) {
        try {
            Properties baseProperties = new Properties(false);
            if (base != null) {
                baseProperties.load(base);
            }
            Properties firstProperties = new Properties(false);
            firstProperties.load(firstChange);
            Properties secondProperties = new Properties(secondChange, false);
            secondProperties.load(secondChange);

            Properties result = useFirstChangeAsBase ? firstProperties : secondProperties;
            Properties otherSource = useFirstChangeAsBase ? secondProperties : firstProperties;

            // first let's iterate over what we have in selected base - we may already find new properties in
            // incoming change vs. base version
            Set<String> keys = new HashSet<>();
            for (Enumeration<?> e = result.propertyNames(); e.hasMoreElements(); ) {
                keys.add((String) e.nextElement());
            }
            for (String key : keys) {
                // treat more important properties (result) as "ours"
                Change state = kindOfChange(key, baseProperties, result, otherSource);
                switch (state) {
                    case NONE:
                    case ADDED_BY_US:
                    case MODIFIED_BY_US:
                        // already reflected in "result" properties
                        break;
                    case DELETED_BY_US:
                    case BOTH_DELETED:
                    case ADDED_BY_THEM:
                        // can't happen in this loop
                        break;
                    case BOTH_ADDED:
                        result.put(key, specialPropertyMerge(key, firstProperties, secondProperties, rollback));
                        break;
                    case BOTH_MODIFIED:
                        // may mean also that we have change vs. removal
                        if (secondProperties.getProperty(key) == null) {
                            result.remove(key);
                        } else {
                            result.put(key, specialPropertyMerge(key, firstProperties, secondProperties, rollback));
                        }
                        break;
                    case DELETED_BY_THEM:
                        result.remove(key);
                        break;
                    case MODIFIED_BY_THEM:
                        result.put(key, otherSource.getProperty(key));
                        break;
                }
            }

            // then we can have additions in less important change, for example if patch adds new properties
            // but we want to preserve layout of properties file from user (it may have user comments for example)
            // we will handle only properties added in "otherSource"
            keys.clear();
            for (Enumeration<?> e = otherSource.propertyNames(); e.hasMoreElements(); ) {
                keys.add((String) e.nextElement());
            }
            for (Enumeration<?> e = result.propertyNames(); e.hasMoreElements(); ) {
                String key = (String) e.nextElement();
                keys.remove(key);
            }
            for (String key : keys) {
                // treat more important properties (result) as "ours"
                Change state = kindOfChange(key, baseProperties, result, otherSource);
                switch (state) {
                    case NONE:
                    case BOTH_DELETED:
                    case BOTH_ADDED:
                    case BOTH_MODIFIED:
                    case ADDED_BY_US:
                    case MODIFIED_BY_US:
                    case DELETED_BY_THEM:
                    case DELETED_BY_US:
                        break;
                    case ADDED_BY_THEM:
                    case MODIFIED_BY_THEM:
                        result.put(key, otherSource.getProperty(key));
                        break;
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            result.store(baos, null);

            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Activator.log(LogService.LOG_ERROR, null, "Problem resolving conflict: " + e.getMessage(), e, true);
        }
        return null;
    }

    /**
     * Special handling of particular key. By default we just pick value from <em>more important</em> set
     * of properties
     * Subclasses ay override this method.
     * @param key
     * @param firstProperties
     * @param secondProperties
     * @param rollback
     * @return
     */
    protected String specialPropertyMerge(String key, Properties firstProperties, Properties secondProperties, boolean rollback) {
        return rollback ? firstProperties.get(key) : secondProperties.get(key);
    }

    /**
     * Checks kind of change at property (instead of line or git blob) level
     * @param key
     * @param baseProperties
     * @param oursProperties
     * @param theirsProperties
     * @return
     */
    private Change kindOfChange(String key, Properties baseProperties, Properties oursProperties, Properties theirsProperties) {
        String base = baseProperties.getProperty(key);
        String ours = oursProperties.getProperty(key);
        String theirs = theirsProperties.getProperty(key);

        if (base == null) {
            if (ours == null && theirs == null) {
                // weird
                return Change.NONE;
            } else if (ours != null && theirs != null) {
                // conflict, but quite unimaginable to have both user and patch add new property with the same name
                // will be resolved by picking version from "second" (more important) change
                return Change.BOTH_ADDED;
            }
            // non-conflict - add new property
            return ours == null ? Change.ADDED_BY_THEM : Change.ADDED_BY_US;
        }

        if (ours == null && theirs == null) {
            // non-conflict - remove property
            return Change.BOTH_DELETED;
        }
        if (ours == null) {
            return !theirs.equals(base) ? Change.BOTH_MODIFIED : Change.DELETED_BY_US;
        }
        if (theirs == null) {
            return !ours.equals(base) ? Change.BOTH_MODIFIED : Change.DELETED_BY_THEM;
        }

        if (ours.equals(base) && theirs.equals(base)) {
            return Change.NONE;
        }
        if (!ours.equals(base) && !theirs.equals(base)) {
            return Change.BOTH_MODIFIED;
        } else {
            return ours.equals(base) ? Change.MODIFIED_BY_THEM : Change.MODIFIED_BY_US;
        }
    }

    private enum Change {
        NONE,
        BOTH_DELETED,
        BOTH_ADDED,
        BOTH_MODIFIED,
        DELETED_BY_US,
        DELETED_BY_THEM,
        ADDED_BY_US,
        ADDED_BY_THEM,
        MODIFIED_BY_US,
        MODIFIED_BY_THEM
    }

}
