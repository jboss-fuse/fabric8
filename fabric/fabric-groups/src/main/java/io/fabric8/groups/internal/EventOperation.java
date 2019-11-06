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

import java.util.Objects;

import io.fabric8.groups.GroupListener;

/**
 * "Event" operation performed on {@link ZooKeeperGroup} is meant to call registered listeners.
 */
class EventOperation implements Operation {

    private final ZooKeeperGroup cache;
    private final GroupListener.GroupEvent event;

    private final String id;
    private final String gid;

    EventOperation(ZooKeeperGroup cache, GroupListener.GroupEvent event) {
        this.cache = cache;
        this.event = event;
        this.id = cache.nextId();
        this.gid = cache.source;
    }

    @Override
    public void invoke() {
        cache.callListeners(event);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EventOperation that = (EventOperation) o;
        return event == that.event;
    }

    @Override
    public int hashCode() {
        return Objects.hash(event);
    }

    @Override
    public String id() {
        return gid + ":" + id;
    }

    @Override
    public String toString() {
        return String.format("[%s:%s EventOperation] { %s, %s }", gid, id, cache.getId(), event.toString());
    }

}
