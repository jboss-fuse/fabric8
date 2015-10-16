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

import org.eclipse.jgit.api.errors.GitAPIException;

/**
 * Operations used to manage git repository that keeps track of applied rollup patches
 * (non-rollup too?)
 * This interface is internal only - to be used by {@link Activator} and services exposed from this bundle.
 */
public interface GitPatchManagementService {

    /**
     * Returns <code>true</code> if patch managment should be performed
     * @return
     */
    boolean isEnabled();

    /**
     * Starts the management if it is required.
     */
    void start() throws IOException, GitAPIException;

    /**
     * Stops the management, cleans up resources.
     */
    void stop();

    /**
     * Initializes necessary git repositories if this hasn't been done before.
     */
    void ensurePatchManagementInitialized();

    /**
     * Called if during startup it was detected that the bundle is already available in etc/startup.properties.
     */
    void cleanupDeployDir() throws IOException;

    /**
     * Invoked at early stage of the framework to check if there are any low-level tasks to be done for pending
     * patches installation (or rollback too)
     */
    void checkPendingPatches();

}
