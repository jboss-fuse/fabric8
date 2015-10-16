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

import io.fabric8.patch.management.BackupService;
import io.fabric8.patch.management.PatchManagement;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.Version;
import org.osgi.framework.startlevel.FrameworkStartLevel;

public class Activator implements BundleActivator {

    public static final int PATCH_MANAGEMENT_START_LEVEL = 2;

    private FrameworkStartLevel sl;
    private int activatedAt = 0;

    private GitPatchManagementService patchManagementService;
    private final Object serviceAccess = new Object();

    // version of this bundle started from etc/startup.properties
    private Version startupVersion;
    // version of this bundle started from deploy/
    private Version deployVersion;
    private StartLevelNotificationFrameworkListener startLevelNotificationFrameworkListener;

    private BundleContext systemContext;
    private ServiceRegistration<PatchManagement> patchManagementRegistration;
    private ServiceRegistration<BackupService> backupServiceRegistration;

    @Override
    public void start(final BundleContext context) throws Exception {
        systemContext = context.getBundle(0).getBundleContext();

        patchManagementService = new GitPatchManagementServiceImpl(context);

        final int targetStartLevel = Integer.parseInt(System.getProperty("org.osgi.framework.startlevel.beginning"));

        sl = context.getBundle(0).adapt(FrameworkStartLevel.class);
        activatedAt = sl.getStartLevel();

        if (!patchManagementService.isEnabled()) {
            System.out.println("[PATCH] Not a Fuse/AMQ installation, ignore now");
            System.out.flush();
            return;
        }

        switch (activatedAt) {
            case PATCH_MANAGEMENT_START_LEVEL:
                // this bundle is configured in etc/startup.properties, we can handle registered tasks
                // like updating critical bundles
                startupVersion = context.getBundle().getVersion();
                // we're started before fileinstall, so we can remove this bundle if it is available in deploy/
                patchManagementService.cleanupDeployDir();
                break;
            default:
                // this bundle was activated from deploy/ directory or osgi:install. But this doesn't mean there's no
                // patch-management bundle configured in etc/startup.properties
                // the point is that when etc/startup.properties already has this bundle, there should be no such bundle in deploy/
                // TODO (there may be newer version however)
                deployVersion = context.getBundle().getVersion();
                break;
        }

        patchManagementService.start();

        patchManagementRegistration = systemContext.registerService(PatchManagement.class, PatchManagement.class.cast(patchManagementService), null);
        backupServiceRegistration = systemContext.registerService(BackupService.class, new FileBackupService(systemContext), null);

        if (startupVersion != null) {
            // we should be at start level 2. let's check if there are any rollup paatches being installed or
            // rolled back - we've got some work to do at this early stage of Karaf
            patchManagementService.checkPendingPatches();
        }

        // this bundle may be started:
        //  - from deploy/ dir using fileinstall thread
        //  - from etc/startup.properties using FelixStartLevel thread (at early SL)
        //  - either of those if patch management is already in etc/startup.properties, but also in deploy/

        if (sl.getStartLevel() == targetStartLevel) {
            // dropped to deploy/ when framework was already at target start-level
            patchManagementService.ensurePatchManagementInitialized();
        } else {
            // let's wait for last start level
            startLevelNotificationFrameworkListener = new StartLevelNotificationFrameworkListener(targetStartLevel);
            systemContext.addFrameworkListener(startLevelNotificationFrameworkListener);
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        synchronized (serviceAccess) {
            patchManagementService.stop();
            patchManagementService = null;
        }
        if (patchManagementRegistration != null) {
            patchManagementRegistration.unregister();
            patchManagementRegistration = null;
        }
        if (backupServiceRegistration != null) {
            backupServiceRegistration.unregister();
            backupServiceRegistration = null;
        }
    }

    /**
     * Listener that takes care of correct patch management initialization when specific start-level is reached
     */
    private class StartLevelNotificationFrameworkListener implements FrameworkListener {

        private final int targetStartLevel;
        private boolean done = false;

        public StartLevelNotificationFrameworkListener(int targetStartLevel) {
            this.targetStartLevel = targetStartLevel;
        }

        @Override
        public void frameworkEvent(FrameworkEvent event) {
            if (event.getType() == FrameworkEvent.STARTLEVEL_CHANGED) {
                if (!done && sl.getStartLevel() == targetStartLevel) {
                    done = true;
                    // last start level reached
                    synchronized (serviceAccess) {
                        if (patchManagementService != null) {
                            patchManagementService.ensurePatchManagementInitialized();
                            systemContext.removeFrameworkListener(this);
                        }
                    }
                }
            }
        }
    }

}
