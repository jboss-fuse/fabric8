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

import java.io.File;

/**
 * More options for conflict resolution
 */
public interface ResolverEx extends Resolver {

    /**
     * Resolves conflict by prefering information from <code>secondChange</code> but with the option to set
     * the <em>layout</em> to either version (important for property/config files)
     * @param firstChange change with lower priority
     * @param base common ancestor of merged files (like current state of file)
     * @param secondChange change with higher priority
     * @param useFirstChangeAsBase if <code>true</code> then even if data (for example property values) is taken
     * from <code>secondChange</code>, layout of the file comes from <code>firstChange</code> (important
     * for property/config files)
     * @return
     */
    String resolve(File firstChange, File base, File secondChange, boolean useFirstChangeAsBase);

}
