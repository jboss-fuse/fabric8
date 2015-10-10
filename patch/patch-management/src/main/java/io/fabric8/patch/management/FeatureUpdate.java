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
 * <p>Command object to update a feature with <code>name</code> defined in previous repository to new version defined
 * in new repository.</p>
 * <p>If <code>newVersion/newRepository</code> are null, this object represents a feature that is not update'able
 * by patch and which must be preserved during patch installation.</p>
 * <p>If <code>name/previousVersion</code> are null, this object represents a feature <strong>repository</strong> that
 * must be added during patch installation.</p>
 */
public class FeatureUpdate {

    private String name;

    private String previousRepository;
    private String previousVersion;
    private String newRepository;
    private String newVersion;

    public FeatureUpdate(String name, String previousRepository, String previousVersion,
                         String newRepository, String newVersion) {
        this.name = name;
        this.previousRepository = previousRepository;
        this.previousVersion = previousVersion;
        this.newRepository = newRepository;
        this.newVersion = newVersion;
    }

    public String getName() {
        return name;
    }

    public String getPreviousRepository() {
        return previousRepository;
    }

    public String getNewRepository() {
        return newRepository;
    }

    public String getPreviousVersion() {
        return previousVersion;
    }

    public String getNewVersion() {
        return newVersion;
    }

}
