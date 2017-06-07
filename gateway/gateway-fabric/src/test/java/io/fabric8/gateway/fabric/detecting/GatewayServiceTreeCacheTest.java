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
package io.fabric8.gateway.fabric.detecting;

import io.fabric8.gateway.ServiceMap;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.junit.Test;

import static org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent.Type.CHILD_ADDED;
import static org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent.Type.CHILD_REMOVED;
import static org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent.Type.CHILD_UPDATED;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GatewayServiceTreeCacheTest {

    @Test
    public void treeCacheEvent() throws Exception {
        String zkPath = "/fabric/registry/clusters/test";
        ServiceMap serviceMap = new ServiceMap();
        CuratorFramework curator = mock(CuratorFramework.class);
        GatewayServiceTreeCache cache = new GatewayServiceTreeCache(curator, zkPath, serviceMap);

        String path = "/fabric/registry/clusters/test/default/0000001";

        // Add container1 - master
        // Add container2 - slave
        cache.treeCacheEvent(event(path, CHILD_ADDED, data("test", "container1", "service1", "service2")));
        cache.treeCacheEvent(event(path, CHILD_ADDED, data("test", "container2")));

        assertEquals(1, serviceMap.getServices("default").size());

        // Remove container1
        // Update container2 - master
        // Add container1 - slave
        cache.treeCacheEvent(event(path, CHILD_REMOVED, data("test", "container1", "service1", "service2")));
        cache.treeCacheEvent(event(path, CHILD_UPDATED, data("test", "container2", "service1", "service2")));
        cache.treeCacheEvent(event(path, CHILD_ADDED, data("test", "container1")));

        assertEquals(1, serviceMap.getServices("default").size());

        // Remove container2
        // Update container1 - master
        // Add container2 - slave
        cache.treeCacheEvent(event(path, CHILD_REMOVED, data("test", "container2", "service1", "service2")));
        cache.treeCacheEvent(event(path, CHILD_UPDATED, data("test", "container1", "service1", "service2")));
        cache.treeCacheEvent(event(path, CHILD_ADDED, data("test", "container2")));

        assertEquals(1, serviceMap.getServices("default").size());

        // Remove container2
        // Add container2 - slave
        cache.treeCacheEvent(event(path, CHILD_REMOVED, data("test", "container2")));
        cache.treeCacheEvent(event(path, CHILD_ADDED, data("test", "container2")));

        assertEquals(1, serviceMap.getServices("default").size());
    }

    private static PathChildrenCacheEvent event(String path, PathChildrenCacheEvent.Type type, String data) {
        PathChildrenCacheEvent event = mock(PathChildrenCacheEvent.class);
        ChildData childData = mock(ChildData.class);
        when(event.getData()).thenReturn(childData);
        when(childData.getPath()).thenReturn(path);
        when(childData.getData()).thenReturn(data.getBytes());
        when(event.getType()).thenReturn(type);
        return event;
    }

    private static String data(String id, String container, String... services) {
        String servicesStr = "";
        boolean first = true;
        for (String service : services) {
            if (first) {
                first = false;
            } else {
                servicesStr += ",";
            }
            servicesStr += "\"" + service + "\"";
        }
        return String.format("{\"id\":\"%s\", \"container\":\"%s\", \"services\":[%s]}",
                id, container, servicesStr);
    }

}
