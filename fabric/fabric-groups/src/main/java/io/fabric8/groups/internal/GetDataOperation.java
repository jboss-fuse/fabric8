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
 * "Get Data" operation performed on {@link ZooKeeperGroup} is meant to synchronize data kept inside
 * {@link ZooKeeperGroup#currentData} map - for single path (unlike {@link RefreshOperation}.
 */
class GetDataOperation implements Operation {

    private final ZooKeeperGroup cache;
    private final String fullPath;

    private final String id;
    private final String gid;

    GetDataOperation(ZooKeeperGroup cache, String fullPath) {
        this.cache = cache;
        this.fullPath = fullPath;
        this.id = cache.nextId();
        this.gid = cache.source;
    }

    @Override
    public void invoke() throws Exception {
        cache.getDataAndStat(fullPath);
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

        GetDataOperation that = (GetDataOperation) o;

        //noinspection RedundantIfStatement
        if (!fullPath.equals(that.fullPath)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        return fullPath.hashCode();
    }

    @Override
    public String toString() {
        return String.format("[%s:%s GetDataOperation] { %s, %s }", gid, id, cache.getId(), fullPath);
    }

}
