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

/**
 * Interface for pluggable functions that may be used to resolve merge/cherry-picking conflicts during patch
 * installation.
 */
public interface Resolver {

    /**
     * <p>Main resolution method that uses standard
     * <a href="https://en.wikipedia.org/wiki/Merge_%28version_control%29#Three-way_merge">3 way merge</a> terminology.</p>
     * <p>Instead of naming the versions <em>ours</em> and <em>theirs</em>, parameters to this method are:<ul>
     *     <li>first - a change that we want to come <strong>first</strong> (may be overriden by "second")</li>
     *     <li>base - common base</li>
     *     <li>second - a change that we want to come <strong>second</strong> (may override "first")</li>
     * </ul>
     * Generally, {@link io.fabric8.patch.management.PatchKind} determinates what version is more important, but
     * sometimes we want to change this - for example, in case of
     * {@link io.fabric8.patch.management.PatchKind#ROLLUP rollup patches}, we prefer patch' version and conflicting user
     * changes rebased on top of new patch are rejected. But we don't want to do it for <code>etc/users.properties</code>
     * where user may have defined additional users.</p>
     * <p>Classes implementing this resolve may do a special handling of merge conflict, for example by comparing
     * <code>*.properties</code> files directly as a set of key=value pairs.</p>
     * @param firstChange change with lower priority
     * @param base common ancestor of merged files (like current state of file)
     * @param secondChange change with higher priority
     * @return resolved version of file or <code>null</code> if standard (R/P patch related) resolution must be used.
     */
    String resolve(File firstChange, File base, File secondChange);

}
