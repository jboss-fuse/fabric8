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
 * <p>Interface to an OSGi service for handling patch-related tasks that has to be done at lower level (e.g., when part of patch installation
 * requires framework restart or an update to critical system files.</p>
 */
public interface PatchTaskManagement {

    /**
     * Add tasks to be performed at startup/restart/xyz
     * @param tasks
     */
    void addTasks(PatchTask... tasks);

}
