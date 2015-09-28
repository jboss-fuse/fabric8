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

/**
 * <p>Patch-Management bundle contains patch-related services to be run at lowest level. The may be run during very early stages
 * of Karaf. Do not expect even OSGi Log Service to be available.</p>
 * <p>All external dependencies (like jgit) are Private-Packaged inside the bundle.</p>
 */
package io.fabric8.patch.management;
