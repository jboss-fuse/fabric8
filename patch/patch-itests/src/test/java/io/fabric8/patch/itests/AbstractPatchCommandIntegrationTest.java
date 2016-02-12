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

import java.io.File;

import io.fabric8.common.util.IOHelpers;
import io.fabric8.itests.support.CommandSupport;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.osgi.framework.BundleContext;

import static org.junit.Assert.fail;

/**
 * Abstract base class for all the patching mechanism integration tests (using the patch:* commands)
 */
public abstract class AbstractPatchCommandIntegrationTest {

    // Time-out for installing/rolling back patches
    private static final long TIMEOUT = 30 * 1000;

    @ArquillianResource
    protected BundleContext context;

    // Install a patch and wait for installation to complete
    protected void install(String name) throws Exception {
        CommandSupport.executeCommand(String.format("patch:install %s", name));
        await(name, true);
    }

    // Rollback a patch and wait for rollback to complete
    protected void rollback(String name) throws Exception {
        CommandSupport.executeCommand(String.format("patch:rollback %s", name));
        await(name, false);
    }

    // Load a patch into the patching service
    protected void load(String name) throws Exception {
        File base = new File(System.getProperty("karaf.base"));
        File temp = new File(base, "data/temp/patches");
        temp.mkdirs();

        File patch = new File(temp, name + ".zip");
        IOHelpers.writeTo(patch, getClass().getResourceAsStream(String.format("/patches/%s.zip", name)));

        CommandSupport.executeCommand(String.format("patch:add %s", patch.toURI().toURL()));
    }


    private void await(String name, Boolean installed) throws Exception {
        long start = System.currentTimeMillis();
        boolean done = false;

        while (!done && System.currentTimeMillis() - start < TIMEOUT) {
            String result = null;
            try {
                result = CommandSupport.executeCommand("patch:list");
            } catch (Exception exception) {
                // when we're updating patch-core, we may use stale patch:list service. Try again then before timeout.
                continue;
            }

            for (String line : result.split("\\r?\\n")) {
                if (line.contains(name) && line.contains(!installed ? "false" : "root")) {
                    done = true;
                    break;
                }
            }

            if (!done) {
                Thread.sleep(100);
            }
        }

        if (!done) {
            fail(String.format("Patch %s does not have installed status %s after %s ms", name, installed, TIMEOUT));
        }
    }

}
