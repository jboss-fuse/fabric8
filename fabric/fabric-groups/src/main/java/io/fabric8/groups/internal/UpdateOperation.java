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
package io.fabric8.groups.internal;

import io.fabric8.groups.NodeState;

/**
 * "Update" operation performed on {@link ZooKeeperGroup} is meant to update data kept inside
 * {@link ZooKeeperGroup#currentData} map for single mapping - addition, update and removal.
 */
class UpdateOperation<T extends NodeState> implements Operation {

    private final ZooKeeperGroup<T> cache;
    private final T node;

    private final String id;
    private final String gid;

    UpdateOperation(ZooKeeperGroup<T> cache, T node) {
        this.cache = cache;
        this.node = node;
        this.id = cache.nextId();
        this.gid = cache.source;
    }

    @Override
    public void invoke() throws Exception {
        cache.doUpdate(node);
    }

    @Override
    public String id() {
        return gid + ":" + id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return String.format("[%s:%s UpdateOperation] { %s, %s }", gid, id, cache.getId(), node);
    }

}
