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
package io.fabric8.internal;

import io.fabric8.service.ComponentConfigurer;
import io.fabric8.zookeeper.curator.Constants;
import io.fabric8.zookeeper.curator.CuratorConfig;
import io.fabric8.zookeeper.curator.ManagedCuratorFramework;
import org.junit.Assert;
import org.junit.Test;
import org.osgi.framework.BundleContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PlaceholderResolverHelpersTest {

    @Test
    public void testExtractSchemes() {
        //Simple key.
        String key = "${zk:container/ip}";
        Set<String> schemes = PlaceholderResolverHelpers.getSchemeForValue(key);
        Assert.assertEquals(schemes.size(), 1);
        Assert.assertTrue(schemes.contains("zk"));

        //Nested key
        key = "${zk:container/${zk:container/resolver}}";
        schemes = PlaceholderResolverHelpers.getSchemeForValue(key);
        Assert.assertEquals(schemes.size(), 1);
        Assert.assertTrue(schemes.contains("zk"));

        //Nested key with multiple schemes
        key = "${profile:${zk:container/foo}";
        schemes = PlaceholderResolverHelpers.getSchemeForValue(key);
        Assert.assertEquals(schemes.size(), 2);
        Assert.assertTrue(schemes.contains("zk"));
        Assert.assertTrue(schemes.contains("profile"));

        key = "file:${runtime.home}/${karaf.default.repository}@snapshots@id=karaf-default,file:${runtime.home}/local-repo@snapshots@id=karaf-local";
        schemes = PlaceholderResolverHelpers.getSchemeForValue(key);
        Assert.assertEquals(schemes.size(), 0);
    }

    @Test
    public void curatorConfiguration() throws Exception {
        ComponentConfigurer configurer = new ComponentConfigurer();

        CuratorConfig cc = new CuratorConfig();
        BundleContext context = mock(BundleContext.class);
        configurer.activate(context);
        Map<String, String> configuration = new HashMap<>();
        configurer.configure(configuration, cc);

        // empty config
        assertNull(cc.getZookeeperUrl());
        assertNull(cc.getZookeeperPassword());
        assertThat(cc.getZookeeperConnectionTimeOut(), equalTo(Constants.DEFAULT_CONNECTION_TIMEOUT_MS));
        assertThat(cc.getZookeeperSessionTimeout(), equalTo(Constants.DEFAULT_SESSION_TIMEOUT_MS));
        assertThat(cc.getZookeeperRetryInterval(), equalTo(Constants.DEFAULT_RETRY_INTERVAL));
        assertThat(cc.getZookeeperRetryMax(), equalTo(Constants.MAX_RETRIES_LIMIT));

        // default config from SCR component
        cc = new CuratorConfig();
        context = mock(BundleContext.class);
        when(context.getProperty("zookeeper.url")).thenReturn("url");
        when(context.getProperty("zookeeper.password")).thenReturn("password");
        when(context.getProperty("zookeeper.retry.max")).thenReturn("42");
        when(context.getProperty("zookeeper.retry.interval")).thenReturn("43");
        when(context.getProperty("zookeeper.connection.timeout")).thenReturn("44");
        when(context.getProperty("zookeeper.session.timeout")).thenReturn("45");
        configurer.activate(context);
        configuration.clear();
        configuration.put(Constants.ZOOKEEPER_URL, "${zookeeper.url}");
        configuration.put(Constants.ZOOKEEPER_PASSWORD, "${zookeeper.password}");
        configuration.put(Constants.RETRY_POLICY_MAX_RETRIES, "${zookeeper.retry.max}");
        configuration.put(Constants.RETRY_POLICY_INTERVAL_MS, "${zookeeper.retry.interval}");
        configuration.put(Constants.CONNECTION_TIMEOUT, "${zookeeper.connection.timeout}");
        configuration.put(Constants.SESSION_TIMEOUT, "${zookeeper.session.timeout}");
        configurer.configure(configuration, cc);

        assertThat(cc.getZookeeperUrl(), equalTo("url"));
        assertThat(cc.getZookeeperPassword(), equalTo("password"));
        assertThat(cc.getZookeeperConnectionTimeOut(), equalTo(44));
        assertThat(cc.getZookeeperSessionTimeout(), equalTo(45));
        assertThat(cc.getZookeeperRetryInterval(), equalTo(43));
        assertThat(cc.getZookeeperRetryMax(), equalTo(42));

        // mixed placeholder and value configuration
        cc = new CuratorConfig();
        context = mock(BundleContext.class);
        when(context.getProperty("zookeeper.password")).thenReturn("password");
        when(context.getProperty("zookeeper.retry.max")).thenReturn("42");
        when(context.getProperty("zookeeper.retry.interval")).thenReturn("43");
        when(context.getProperty("zookeeper.connection.timeout")).thenReturn("44");
        when(context.getProperty("zookeeper.session.timeout")).thenReturn("45");
        configurer.activate(context);
        configuration.clear();
        configuration.put(Constants.ZOOKEEPER_URL, "url2");
        configuration.put(Constants.ZOOKEEPER_PASSWORD, "${zookeeper.password}");
        configuration.put(Constants.RETRY_POLICY_MAX_RETRIES, "${zookeeper.retry.max}");
        configuration.put(Constants.RETRY_POLICY_INTERVAL_MS, "${zookeeper.retry.interval}");
        configuration.put(Constants.CONNECTION_TIMEOUT, "444");
        configuration.put(Constants.SESSION_TIMEOUT, "${zookeeper.session.timeout}");
        configurer.configure(configuration, cc);

        assertThat(cc.getZookeeperUrl(), equalTo("url2"));
        assertThat(cc.getZookeeperPassword(), equalTo("password"));
        assertThat(cc.getZookeeperConnectionTimeOut(), equalTo(444));
        assertThat(cc.getZookeeperSessionTimeout(), equalTo(45));
        assertThat(cc.getZookeeperRetryInterval(), equalTo(43));
        assertThat(cc.getZookeeperRetryMax(), equalTo(42));
    }

}
