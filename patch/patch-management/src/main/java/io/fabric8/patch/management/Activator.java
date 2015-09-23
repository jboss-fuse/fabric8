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

import io.fabric8.patch.management.impl.GitPatchManagementService;
import io.fabric8.patch.management.impl.GitPatchManagementServiceImpl;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.Version;
import org.osgi.framework.startlevel.FrameworkStartLevel;

public class Activator implements BundleActivator {

    private static final int PATCH_MANAGEMENT_START_LEVEL = 2;

    private FrameworkStartLevel sl;
    private int activatedAt = 0;
    private BundleContext systemContext;

    private GitPatchManagementService patchManagementService;

    // version of this bundle started from etc/startup.properties
    private Version startupVersion;
    // version of this bundle started from deploy/
    private Version deployVersion;

    @Override
    public void start(final BundleContext context) throws Exception {
        patchManagementService = new GitPatchManagementServiceImpl(context);

        final int targetStartLevel = Integer.parseInt(System.getProperty("org.osgi.framework.startlevel.beginning"));

        systemContext = context.getBundle(0).getBundleContext();
        sl = context.getBundle(0).adapt(FrameworkStartLevel.class);
        activatedAt = sl.getStartLevel();

        if (!patchManagementService.isEnabled()) {
            System.out.println("[PATCH] Not a Fuse/AMQ installation, ignore now");
            return;
        }

        switch (activatedAt) {
            case 0:
                // PANIC!
                break;
            case PATCH_MANAGEMENT_START_LEVEL:
                // this bundle is configured in etc/startup.properties, we can handle registered tasks
                // like updating critical bundles
                startupVersion = context.getBundle().getVersion();
                // TODO check if there's not newer version of this bundle in ${karaf.home}/deploy as well
                break;
            default:
                // this bundle is dropped into deploy/ directory, we have to do initial preparation
                deployVersion = context.getBundle().getVersion();
                break;
        }

        patchManagementService.start();

        // this bundle may be started:
        //  - from deploy/ dir using fileinstall thread
        //  - from etc/startup.properties using FelixStartLevel thread (at early SL)
        //  - either of those if patch management is already in etc/startup.properties, but also in deploy/

        if (sl.getStartLevel() == targetStartLevel) {
            // dropped to deploy/ when framework was already at target start-level
            System.out.println("[PATCH] STARTED (1) at " + sl.getStartLevel());
            patchManagementService.ensurePatchManagementInitialized();
        } else {
            // let's wait for last start level
            context.addFrameworkListener(new FrameworkListener() {
                @Override
                public void frameworkEvent(FrameworkEvent event) {
                    if (event.getType() == FrameworkEvent.STARTLEVEL_CHANGED) {
                        if (sl.getStartLevel() == targetStartLevel) {
                            // last start level reached
                            System.out.println("[PATCH] STARTED (2) at " + sl.getStartLevel());
                            patchManagementService.ensurePatchManagementInitialized();
                        }
                    }
                }
            });
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        patchManagementService.stop();
        patchManagementService = null;
    }

}
