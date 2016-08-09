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
package io.fabric8.patch.management.impl;

import java.io.File;
import java.io.IOException;

import io.fabric8.patch.management.EnvService;
import io.fabric8.patch.management.EnvType;
import org.apache.commons.io.FileUtils;
import org.osgi.framework.BundleContext;

public class DefaultEnvService implements EnvService {

    private final BundleContext systemContext;
    private final File karafHome;
    private final File karafBase;

    public DefaultEnvService(BundleContext systemContext, File karafHome, File karafBase) {
        this.systemContext = systemContext;
        this.karafHome = karafHome;
        this.karafBase = karafBase;
    }

    @Override
    public EnvType determineEnvironmentType() throws IOException {
        if (Boolean.getBoolean("patching.disabled")) {
            return EnvType.UNKNOWN;
        }

        File localGitRepository = new File(systemContext.getProperty("karaf.data"), "git/local/fabric");
        boolean isChild = isChild(systemContext);
        if (localGitRepository.isDirectory() && new File(localGitRepository, ".git").isDirectory()) {
            // we have git repository of current container - is it initalized?
            // TODO: maybe check if .git/config contains remote "origin" ending with "/git/fabric/"?
            if (hasBranch(localGitRepository, "master") && hasBranch(localGitRepository, "1.0")) {
                if (isChild) {
                    return EnvType.FABRIC_CHILD;
                } else {
                    // is it enough?
                    if (new File(karafHome, "bin/fuse").isFile()
                            || new File(karafHome, "bin/fuse.bat").isFile()) {
                        return EnvType.FABRIC_FUSE;
                    } else if (new File(karafHome, "bin/amq").isFile()
                            || new File(karafHome, "bin/amq.bat").isFile()) {
                        return EnvType.FABRIC_AMQ;
                    } else if (new File(karafHome, "bin/fabric8").isFile()
                            || new File(karafHome, "bin/fabric8.bat").isFile()) {
                        return EnvType.FABRIC_FABRIC8;
                    }
                }
            }
        }

        return isChild ? EnvType.STANDALONE_CHILD : EnvType.STANDALONE;
    }

    /**
     * Checks if git repository (a dir containing <code>.git</code> subdir) has particular branch
     * @param localGitRepository
     * @param name
     * @return
     */
    private boolean hasBranch(File localGitRepository, String name) {
        boolean separateRef = new File(localGitRepository, ".git/refs/heads/" + name).isFile();
        boolean packedRef = false;
        try {
            String packedRefsFile = FileUtils.readFileToString(new File(localGitRepository, ".git/packed-refs"));
            packedRef = packedRefsFile != null && packedRefsFile.contains("refs/heads/" + name);
        } catch (IOException ignored) {
        }
        return separateRef || packedRef;
    }

    /**
     * Using some String manipulation it returns whether we have Karaf child container
     * @param systemContext
     * @return
     */
    private boolean isChild(BundleContext systemContext) {
        String karafName = systemContext.getProperty("karaf.name");
        String karafInstances = systemContext.getProperty("karaf.instances");
        String karafHome = systemContext.getProperty("karaf.home");
        String karafBase = systemContext.getProperty("karaf.base");
        if (!karafBase.equals(karafHome)) {
            if ((karafInstances + File.separatorChar + karafName).equals(karafBase)) {
                return true;
            }
        }
        return false;
    }

}
