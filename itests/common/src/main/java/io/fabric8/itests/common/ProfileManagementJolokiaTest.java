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
package io.fabric8.itests.common;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;

import io.fabric8.api.mxbean.ProfileManagement;
import io.fabric8.jolokia.client.JolokiaMXBeanProxy;

import javax.management.ObjectName;

import io.fabric8.utils.Base64Encoder;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test client side {@link ProfileManagement} test via jolokia
 *
 * @since 15-Sep-2014
 */
@RunAsClient
@RunWith(Arquillian.class)
public class ProfileManagementJolokiaTest extends AbstractProfileManagementTest {

    public static Logger LOG = LoggerFactory.getLogger(ProfileManagementJolokiaTest.class);

    static final String jmxServiceURL = "http://127.0.0.1:8181/jolokia";
    static final String[] credentials = new String[] { "admin", "admin" };

    static ProfileManagement proxy;

    @Before
    public void waitForJolokia() {
        for (int n = 0; n < 10; n++) {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(jmxServiceURL).openConnection();
                con.setRequestProperty("Authorization", "Basic " + Base64Encoder.encode(credentials[0] + ":" + credentials[1]));
                con.connect();
                int rc = con.getResponseCode();
                if (rc != HttpURLConnection.HTTP_OK) {
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e.getMessage(), e);
            } catch (IOException e) {
                LOG.warn(e.getMessage());
            }
        }
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        proxy = JolokiaMXBeanProxy.getMXBeanProxy(jmxServiceURL, new ObjectName(ProfileManagement.OBJECT_NAME), ProfileManagement.class, credentials[0], credentials[1]);
    }

    @Override
    ProfileManagement getProxy() {
        return proxy;
    }
}
