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

import java.io.File;

import io.fabric8.patch.management.EnvService;
import io.fabric8.patch.management.EnvType;
import org.osgi.framework.BundleContext;

public class DefaultEnvService implements EnvService {

    private final BundleContext systemContext;
    private final File karafHome;
    private final File patchesDir;

    public DefaultEnvService(BundleContext systemContext, File karafHome, File patchesDir) {
        this.systemContext = systemContext;
        this.karafHome = karafHome;
        this.patchesDir = patchesDir;
    }

    @Override
    public EnvType determineEnvironmentType() {
        File localGitRepository = new File(systemContext.getProperty("karaf.data"), "git/local/fabric");
        if (localGitRepository.isDirectory() && new File(localGitRepository, ".git").isDirectory()) {
            // we start in fabric mode, we have main fabric git repo (which doesn't mean we didn't start
            // as standalone before and created standalone patches/.management/history repo already)
            return EnvType.FABRIC;
        } else {
            return EnvType.STANDALONE;
        }
    }

}
