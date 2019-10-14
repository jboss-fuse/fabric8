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

/**
 * "Refresh" operation performed on {@link ZooKeeperGroup} is meant to synchronize <strong>all</strong>
 * data kept inside {@link ZooKeeperGroup#currentData} as mapping between full path and ZK data.
 * Removals, updates and additions are handled.
 */
class RefreshOperation implements Operation {

    private final ZooKeeperGroup cache;
    private final ZooKeeperGroup.RefreshMode mode;

    private final String id;
    private final String gid;

    RefreshOperation(ZooKeeperGroup cache, ZooKeeperGroup.RefreshMode mode) {
        this.cache = cache;
        this.mode = mode;
        this.id = cache.nextId();
        this.gid = cache.source;
    }

    @Override
    public void invoke() throws Exception {
        cache.refresh(mode);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        RefreshOperation that = (RefreshOperation) o;

        //noinspection RedundantIfStatement
        if (mode != that.mode) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return mode.hashCode();
    }

    @Override
    public String toString() {
        return String.format("[%s:%s RefreshOperation] { %s, %s }", gid, id, cache.getId(), mode);
    }

}
