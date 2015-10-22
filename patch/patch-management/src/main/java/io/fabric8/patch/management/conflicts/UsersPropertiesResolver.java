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
package io.fabric8.patch.management.conflicts;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import io.fabric8.patch.management.impl.Activator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.osgi.service.log.LogService;

/**
 * Conflict resolver for <code>etc/users.properties</code> file - we take existing users properties (uncommented users)
 * but we try to copy comments from new file and check whether the example (commented out) <code>admin</code>
 * user has additional roles.
 */
public class UsersPropertiesResolver implements Resolver {

    @Override
    public String resolve(File patchChange, File base, File customChange) {
        Properties ourUsers = new Properties();
        FileInputStream fis = null;
        StringBuilder sb = new StringBuilder();
        Set<String> groupsFromPatch = new LinkedHashSet<>();
        try {
            fis = new FileInputStream(customChange);
            ourUsers.load(fis);
            Set<String> users = ourUsers.stringPropertyNames();

            // do not expect existing users from patch (theirs), do not expect anything besides comments and
            // empty lines here
            List<String> patchVersion = FileUtils.readLines(patchChange);
            for (String line : patchVersion) {
                if (line != null && line.trim().startsWith("#")) {
                    sb.append(line).append("\n");
                    if (line.trim().substring(1).indexOf('=') > 0) {
                        String possibleUserDefinition = line.trim().substring(1);
                        // try to parse commented out "admin" user to see if patch recommends new set of groups
                        String user = possibleUserDefinition.substring(0, possibleUserDefinition.indexOf('=')).trim();
                        String groups = possibleUserDefinition.substring(possibleUserDefinition.indexOf('=') + 1).trim();
                        if ("admin".equals(user)) {
                            String[] defaultPasswordAndDefaultGroups = groups.split("\\s*,\\s*");
                            String[] defaultGroups = new String[defaultPasswordAndDefaultGroups.length - 1];
                            System.arraycopy(defaultPasswordAndDefaultGroups, 1, defaultGroups, 0, defaultGroups.length);
                            groupsFromPatch.addAll(Arrays.asList(defaultGroups));
                        }
                    }
                } else if (line != null && "".equals(line.trim())) {
                    sb.append(line).append("\n");
                }
            }

            // now copy existing users
            for (String user : users) {
                String groups = ourUsers.getProperty(user);
                if (groups != null && !"".equals(groups)) {
                    Set<String> ourGroups = new LinkedHashSet<>(Arrays.asList(groups.split("\\s*,\\s*")));
                    if ("admin".equals(user)) {
                        ourGroups.addAll(groupsFromPatch);
                    }
                    String newGroups = "";
                    for (String newGroup : ourGroups) {
                        newGroups += ", " + newGroup;
                    }
                    sb.append(user).append(" =").append("".equals(newGroups) ? "" : newGroups.substring(1)).append("\n");
                } else {
                    sb.append(user).append(" =").append("\n");
                }
            }

            return sb.toString();
        } catch (IOException e) {
            String message = String.format("Problem resolving conflict between \"%s\" and \"%s\": %s",
                    patchChange.toString(),
                    customChange.toString(),
                    e.getMessage());
            Activator.log(LogService.LOG_ERROR, null, message, e, true);
        } finally {
            IOUtils.closeQuietly(fis);
        }
        return null;
    }

    @Override
    public String toString() {
        return "etc/users.properties resolver";
    }

}
