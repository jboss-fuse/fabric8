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
package io.fabric8.patch.itests;

import io.fabric8.api.gravia.ServiceLocator;
import io.fabric8.common.util.IOHelpers;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.Service;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.Before;
import org.osgi.framework.BundleContext;

import java.io.File;

import static org.junit.Assert.fail;

/**
 * Abstract base class for all the patching mechanism integration tests (using the OSGi Service)
 */
public abstract class AbstractPatchIntegrationTest {

    // Time-out for installing/rolling back patches
    private static final long TIMEOUT = 30 * 1000;

    @ArquillianResource
    protected BundleContext context;

    // The patch service
    protected Service service;

    @Before
    public void setupService() throws Exception {
        // let's grab the patch service and get testing, shall we?
        service = ServiceLocator.awaitService(context, Service.class);
    }

    // Install a patch and wait for installation to complete
    protected void install(String name) throws Exception {
        Patch patch = service.getPatch(name);
        service.install(patch, false, false);

        long start = System.currentTimeMillis();
        while (!patch.isInstalled() && System.currentTimeMillis() - start < TIMEOUT) {
            Thread.sleep(100);
        }
        if (!patch.isInstalled()) {
            fail(String.format("Patch '%s' did not installed within %s ms", name, TIMEOUT));
        }
    }

    // Rollback a patch and wait for rollback to complete
    protected void rollback(String name) throws Exception {
        Patch patch = service.getPatch(name);
        service.rollback(patch, false, false);

        long start = System.currentTimeMillis();
        while (patch.isInstalled() && System.currentTimeMillis() - start < TIMEOUT) {
            Thread.sleep(100);
        }
        if (patch.isInstalled()) {
            fail(String.format("Patch '%s' did not roll back within %s ms", name, TIMEOUT));
        }
    }

    // Load a patch into the patching service
    protected void load(String name) throws Exception {
        File base = new File(System.getProperty("karaf.base"));
        File temp = new File(base, "data/temp/patches");
        temp.mkdirs();

        File patch = new File(temp, name + ".zip");
        IOHelpers.writeTo(patch, getClass().getResourceAsStream(String.format("/patches/%s.zip", name)));

        service.download(patch.toURI().toURL());
    }

}
