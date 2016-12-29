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
import java.io.FilenameFilter;
import java.io.PrintStream;

import io.fabric8.patch.management.BackupService;
import io.fabric8.patch.management.PatchManagement;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.Version;
import org.osgi.framework.startlevel.FrameworkStartLevel;
import org.osgi.service.log.LogService;
import org.osgi.util.tracker.ServiceTracker;

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

    private static Bundle bundle;
    private BundleContext systemContext;
    private ServiceRegistration<PatchManagement> patchManagementRegistration;
    private ServiceRegistration<BackupService> backupServiceRegistration;

    private volatile static ServiceTracker logServiceTracker = null;

    @Override
    @SuppressWarnings("unchecked")
    public void start(final BundleContext context) throws Exception {
        bundle = context.getBundle();
        systemContext = context.getBundle(0).getBundleContext();

        logServiceTracker = new ServiceTracker(context, "org.osgi.service.log.LogService", null);
        logServiceTracker.open();

        patchManagementService = new GitPatchManagementServiceImpl(context);

        final int targetStartLevel = Integer.parseInt(System.getProperty("org.osgi.framework.startlevel.beginning"));

        sl = context.getBundle(0).adapt(FrameworkStartLevel.class);
        activatedAt = sl.getStartLevel();

        if (!patchManagementService.isEnabled()) {
            log(LogService.LOG_INFO, "\nPatch management is disabled");
            return;
        }

        try {
            class E7 extends Exception {
                public E7(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
                    super(message, cause, enableSuppression, writableStackTrace);
                }
            }
            new E7("test", null, false, false);
        } catch (Throwable t) {
            File[] files = new File(System.getProperty("karaf.home"), "lib/endorsed").listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.contains("karaf.exception");
                }
            });
            if (files != null && files.length > 0) {
                log2(LogService.LOG_WARNING, "Please remove \"" + files[0].getName() + "\" from lib/endorsed directory and restart. Patching won't succeed with this version of endorsed library.");
            } else {
                log2(LogService.LOG_WARNING, "Available java.lang.Exception class is not compatible with JDK7 and later. Patching won't succeed. Please restart.");
            }
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
        if (logServiceTracker != null) {
            logServiceTracker.close();
            logServiceTracker = null;
        }
    }

    /**
     * Tries to log using OSGi logging service, falls back to stdout/stderr
     * @param level
     * @param message
     */
    public static void log(int level, String message) {
        log(level, null, message, null, false);
    }

    /**
     * Tries to log using OSGi logging service, logs to stdout/stderr anyway
     * @param level
     * @param message
     */
    public static void log2(int level, String message) {
        log(level, null, message, null, true);
    }

    /**
     * Tries to log using OSGi logging service, falls back to stdout/stderr
     * @param level
     * @param bundle
     * @param message
     * @param throwable
     * @param twoWay
     */
    public static void log(int level, Bundle bundle, String message, Throwable throwable, boolean twoWay) {
        ServiceTracker tracker = logServiceTracker;
        if (bundle == null) {
            bundle = Activator.bundle;
        }
        if (tracker != null) {
            Object service = tracker.getService();
            if (service != null) {
                ((LogService)service).log(level, message, throwable);
                if (!twoWay) {
                    return;
                }
            }
        }

        // code from org.apache.felix.scr.impl.Activator.log() {
        // output depending on level
        PrintStream out = (level == LogService.LOG_ERROR) ? System.err : System.out;

        // level as a string
        StringBuffer buf = new StringBuffer();
        switch (level) {
            case (LogService.LOG_DEBUG):
                buf.append("DEBUG: ");
                break;
            case (LogService.LOG_INFO):
                buf.append("INFO : ");
                break;
            case (LogService.LOG_WARNING):
                buf.append("WARN : ");
                break;
            case (LogService.LOG_ERROR):
                buf.append("ERROR: ");
                break;
        }

        // bundle information
        if (bundle != null) {
            buf.append(bundle.getSymbolicName());
            buf.append(" (");
            buf.append(bundle.getBundleId());
            buf.append("): ");
        }

        // the message
        buf.append(message);

        out.println(buf);
        if (throwable != null) {
            throwable.printStackTrace(out);
        }
        out.flush();
        // }
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
