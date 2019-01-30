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
package io.fabric8.zookeeper.utils;

import java.security.GeneralSecurityException;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class ZookeeperUtilsTest {

    public static Logger LOG = LoggerFactory.getLogger(ZookeeperUtilsTest.class);

    @Test
    public void derivePasswordsFromConfiguration() throws GeneralSecurityException {
        Properties props = new Properties();
        props.setProperty("fabric.zookeeper.pid", "io.fabric8.zookeeper.server-0001");
        props.setProperty("initLimit", "10");
        props.setProperty("purgeInterval", "0");
        props.setProperty("quorum.auth.enableSasl", "true");
        props.setProperty("server.1", "everfree.forest:2888:3888");
        props.setProperty("server.2", "rhokd:2888:3888");
        props.setProperty("server.3", "rhokd:2889:3889");
        props.setProperty("service.factoryPid", "io.fabric8.zookeeper.server");
        props.setProperty("snapRetainCount", "3");
        props.setProperty("syncLimit", "5");
        props.setProperty("tickTime", "2000");

        Properties props1 = new Properties(props);
        props1.setProperty("dataDir", "/fuse/root/zookeeper/0001");
        props1.setProperty("component.id", "1");
        props1.setProperty("service.pid", "io.fabric8.zookeeper.server.0935abb4-838d-4642-8a30-95ac9843bf3a");
        props1.setProperty("server.id", "1");
        props1.setProperty("clientPort", "2181");
        props1.setProperty("clientPortAddress", "0.0.0.0");
        Properties props2 = new Properties(props);
        props2.setProperty("dataDir", "/fuse/ssh1/zookeeper/0001");
        props2.setProperty("component.id", "2");
        props2.setProperty("service.pid", "io.fabric8.zookeeper.server.1994afd2-355e-46a9-809a-d682cdc337d2");
        props2.setProperty("server.id", "2");
        props2.setProperty("clientPort", "2182");
        props2.setProperty("clientPortAddress", "0.0.0.0");
        Properties props3 = new Properties(props);
        props3.setProperty("dataDir", "/fuse/ssh2/zookeeper/0001");
        props3.setProperty("component.id", "3");
        props3.setProperty("service.pid", "io.fabric8.zookeeper.server.d0aa748f-ede4-4518-9f97-ab9e03666dce");
        props3.setProperty("server.id", "3");
        props3.setProperty("clientPort", "2183");
        props3.setProperty("clientPortAddress", "192.168.0.1");

        // each peer should derive 3 different passwords - but all peers should derive the same passwords
        Set<String> passwords = new LinkedHashSet<>();
        passwords.add(ZooKeeperUtils.derivePeerPassword(props1, 1));
        passwords.add(ZooKeeperUtils.derivePeerPassword(props1, 2));
        passwords.add(ZooKeeperUtils.derivePeerPassword(props1, 3));
        passwords.add(ZooKeeperUtils.derivePeerPassword(props2, 1));
        passwords.add(ZooKeeperUtils.derivePeerPassword(props2, 2));
        passwords.add(ZooKeeperUtils.derivePeerPassword(props2, 3));
        passwords.add(ZooKeeperUtils.derivePeerPassword(props3, 1));
        passwords.add(ZooKeeperUtils.derivePeerPassword(props3, 2));
        passwords.add(ZooKeeperUtils.derivePeerPassword(props3, 3));

        assertThat(passwords.size(), equalTo(3));
        int i = 1;
        for (String p : passwords) {
            LOG.info("peer." + (i++) + " / " + p);
        }
    }

    @Test
    public void pathOperations() {
        assertThat(ZooKeeperUtils.getParent("/fabric/node/a/b/c"), equalTo("/fabric/node/a/b"));
        assertThat(ZooKeeperUtils.getParent("/fabric/node/"), equalTo("/fabric"));
        assertThat(ZooKeeperUtils.getParent("/fabric/node"), equalTo("/fabric"));
        assertThat(ZooKeeperUtils.getParent("/fabric"), equalTo("/"));
        assertThat(ZooKeeperUtils.getParent("/"), equalTo("/"));
    }

}
