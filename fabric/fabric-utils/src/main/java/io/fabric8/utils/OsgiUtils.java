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
package io.fabric8.utils;

import org.osgi.framework.*;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationEvent;
import org.osgi.service.cm.ConfigurationListener;
import org.osgi.util.tracker.ServiceTracker;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class OsgiUtils {

    public static final Long SERVICE_TIMEOUT = 20000L;

    public void waitForSerice(Class type, long timeout) {
        waitForSerice(type, null, timeout);
    }

    public static void waitForSerice(Class type) {
        waitForSerice(type, null, SERVICE_TIMEOUT);
    }

    public static void waitForSerice(Class type, String filter, long timeout) {
        BundleContext bundleContext = getBundleContext();
        ServiceTracker tracker = null;
        try {
            String flt;
            if (filter != null) {
                if (filter.startsWith("(")) {
                    flt = "(&(" + org.osgi.framework.Constants.OBJECTCLASS + "=" + type.getName() + ")" + filter + ")";
                } else {
                    flt = "(&(" + org.osgi.framework.Constants.OBJECTCLASS + "=" + type.getName() + ")(" + filter + "))";
                }
            } else {
                flt = "(" + org.osgi.framework.Constants.OBJECTCLASS + "=" + type.getName() + ")";
            }
            Filter osgiFilter = FrameworkUtil.createFilter(flt);
            tracker = new ServiceTracker(bundleContext, osgiFilter, null);
            tracker.open(true);
            if (tracker.waitForService(timeout) == null) {
                throw new RuntimeException("Gave up waiting for service " + flt);
            }
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException("Invalid filter", e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            tracker.close();
        }
    }

    /**
     * Returns the bundle context.
     *
     * @return
     */
    private static BundleContext getBundleContext() {
        return FrameworkUtil.getBundle(OsgiUtils.class).getBundleContext();
    }

    /**
     * Explode the dictionary into a ,-delimited list of key=value pairs
     */
    private static String explode(Dictionary dictionary) {
        Enumeration keys = dictionary.keys();
        StringBuffer result = new StringBuffer();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            result.append(String.format("%s=%s", key, dictionary.get(key)));
            if (keys.hasMoreElements()) {
                result.append(", ");
            }
        }
        return result.toString();
    }

    /**
     * Provides an iterable collection of references, even if the original array is null
     */
    private static Collection<ServiceReference> asCollection(ServiceReference[] references) {
        return references != null ? Arrays.asList(references) : Collections.<ServiceReference>emptyList();
    }

    /**
     * Updates CM configuration and waits for CM_UPDATE event to be send
     * @param bundleContext
     * @param config
     * @param properties
     * @param timeout
     * @param unit
     * @return
     */
    public static boolean updateCmConfigurationAndWait(BundleContext bundleContext, final Configuration config,
                                                       Dictionary<String, Object> properties,
                                                       long timeout, TimeUnit unit) throws IOException, InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final ServiceRegistration<ConfigurationListener> registration = bundleContext.registerService(ConfigurationListener.class, new ConfigurationListener() {
            @Override
            public void configurationEvent(ConfigurationEvent event) {
                if (event.getType() == ConfigurationEvent.CM_UPDATED && event.getPid() != null
                        && event.getPid().equals(config.getPid())) {
                    latch.countDown();
                }
            }
        }, null);

        config.update(properties);

        try {
            return latch.await(timeout, unit);
        } finally {
            registration.unregister();
        }
    }

}
