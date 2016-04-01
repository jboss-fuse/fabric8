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
package io.fabric8.patch.management;

/**
 * <p>Kind of patch</p>
 * <p>Rollup patches effectively turn one installation into newer version updating almost all of the files</p>
 * <p>Non-rollup patches are medium size fixes that usually update OSGi bundles</p>
 */
public enum PatchKind {
    ROLLUP,
    NON_ROLLUP
}
