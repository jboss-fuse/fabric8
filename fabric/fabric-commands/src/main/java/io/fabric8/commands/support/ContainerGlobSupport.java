/*
 *  Copyright 2005-2017 Red Hat, Inc.
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
package io.fabric8.commands.support;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

import io.fabric8.api.Container;
import io.fabric8.api.FabricService;

/**
 * Helper class to deal with lists of container names or globs (patterns)
 */
public class ContainerGlobSupport {

    /**
     * <p>Converts a list of possibly wildcard container names into list of available container names.</p>
     * <p>It also checks if the expanded list has at least one element</p>
     */
    public static  Collection<String> expandGlobNames(FabricService fabricService, List<String> containerNames) {
        Collection<String> expandedNames = new LinkedHashSet<String>();
        if (containerNames == null) {
            System.out.println("Please specify container name(s).");
            return expandedNames;
        }
        boolean globUsed = false;
        for (String name: containerNames) {
            if (name.contains("*") || name.contains("?")) {
                globUsed = true;
                expandedNames.addAll(matchedAvailableContainers(fabricService, name));
            } else {
                expandedNames.add(name);
            }
        }
        if (expandedNames.size() == 0) {
            if (globUsed) {
                System.out.println("Please specify container name(s). Your pattern didn't match any container name.");
            } else {
                System.out.println("Please specify container name(s).");
            }
        } else {
            System.out.println("The list of container names: " + expandedNames.toString());
        }

        return expandedNames;
    }

    /**
     * Returns a list of all available containers matching simple pattern where {@code *} matches any substring
     * and {@code ?} matches single character.
     */
    public static List<String> matchedAvailableContainers(FabricService fabricService, String pattern) {
        LinkedList<String> result = new LinkedList<String>();
        for (Container c: fabricService.getContainers()) {
            String name = c.getId();
            if (matches(pattern, name))
                result.add(name);
        }
        return result;
    }

    /**
     * Simple "glob" pattern matching
     */
    public static boolean matches(String globPattern, String name) {
        String re = "^" + globPattern.replace(".", "\\.").replace("?", ".?").replace("*", ".*") + "$";
        return name.matches(re);
    }

}
