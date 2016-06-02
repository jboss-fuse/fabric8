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

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;

/**
 * <p>Manager-style class that can be used to resolve conflicts during 3-way merge. This resolver can help with
 * resolving conflicts for files inside well-known base - which is <code>${karaf.home}</code></p>
 * <p>The path that is used to find resolvers is a relative path inside <code>${karaf.home}</code>, for example
 * "etc/users.properties"</p>
 */
public class ConflictResolver {

    private Map<String, Resolver> builtInResolvers = new HashMap<>();

    public ConflictResolver() {
        builtInResolvers.put("etc/users.properties", new UsersPropertiesResolver());
        builtInResolvers.put("bin/setenv", new ChooseUserVersionResolver());
        builtInResolvers.put("bin/setenv.bat", new ChooseUserVersionResolver());
        PropertiesFileResolver resolver = new PropertiesFileResolver();
        builtInResolvers.put("*.properties", resolver);
        builtInResolvers.put("*.cfg", resolver);
    }

    /**
     * If there's dedicated resolver for a path relative to <code>${karaf.home}</code>, return it. If there's no
     * resolver for path, we try resolver by extension.
     * @param path
     * @return
     */
    public Resolver getResolver(String path) {
        Resolver resolver = builtInResolvers.get(path);
        if (resolver == null) {
            String ext = FilenameUtils.getExtension(path);
            resolver = builtInResolvers.get("*." + ext);
        }
        return resolver;
    }

}
