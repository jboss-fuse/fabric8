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
package io.fabric8.zookeeper.curator;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.fabric8.internal.PlaceholderResolverHelpers;
import io.fabric8.service.ComponentConfigurer;
import org.junit.Assert;
import org.junit.Test;
import org.osgi.framework.BundleContext;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PlaceholderResolverHelpersTest {

    @Test
    public void managedCuratorConfiguration() throws Exception {
        ManagedCuratorFramework mcf = new ManagedCuratorFramework();

        Map<String, Object> configuration = new HashMap<>();
        configuration.put("zookeeper.connection.timeout", "100");
        Map<String, ?> adjustedConfiguration = mcf.adjust(configuration);
        assertThat((String)adjustedConfiguration.get(Constants.CONNECTION_TIMEOUT), equalTo("100"));

        configuration.clear();
        configuration.put("zookeeper.connection.timeout", "100");
        configuration.put("zookeeper.connection.time.out", "200"); // higher priority for backward compatibility
        adjustedConfiguration = mcf.adjust(configuration);
        assertThat((String)adjustedConfiguration.get(Constants.CONNECTION_TIMEOUT), equalTo("200"));

        configuration.clear();
        configuration.put("zookeeper.connection.time.out", "100");
        adjustedConfiguration = mcf.adjust(configuration);
        assertThat((String)adjustedConfiguration.get(Constants.CONNECTION_TIMEOUT), equalTo("100"));
    }

}
