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
package io.fabric8.patch.management.impl;

import java.io.IOException;

/**
 * Operations used to manage git repository that keeps track of applied rollup patches
 * (non-rollup too?)
 * These operations are used both by the containing bundle, to ensure that git patch-management is enabled,
 * but also by other bundles (like patch-commands) to interact with this low-level bundle when applying patches.
 */
public interface GitPatchManagementService {

    /**
     * Returns <code>true</code> if patch managment should be performed
     * @return
     */
    boolean isEnabled();

    /**
     * Starts the management if it is required. Prepares resources (like {@link org.eclipse.jgit.api.Git} instance.
     */
    void start();

    /**
     * Stops the management, cleans up resources
     */
    void stop();

    /**
     * Initializes necessary git repositories if this hasn't been done before
     */
    void ensurePatchManagementInitialized();

    /**
     * Called if during startup it was detected that the bundle is already available in etc/startup.properties
     */
    void cleanupDeployDir() throws IOException;

}
