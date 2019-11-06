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

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Operation that aggregates several {@link Operation operations} to be performed inside single task passed
 * to thread executor.
 */
public class CompositeOperation implements Operation {

    public static Logger LOG = LoggerFactory.getLogger("io.fabric8.cluster");

    private final ZooKeeperGroup cache;

    private Operation[] operations;

    private final String id;
    private final String gid;

    public CompositeOperation(ZooKeeperGroup cache, Operation ... operations) {
        this.cache = cache;
        this.operations = operations;
        this.id = cache.nextId();
        this.gid = cache.source;
    }

    @Override
    public void invoke() throws Exception {
        for (Operation op : operations) {
            String tn = Thread.currentThread().getName();
            try {
                LOG.debug(cache + ": invoking " + op);
                String tn2 = tn.substring(0, tn.length() - 1);
                Thread.currentThread().setName(tn2 + "/" + op.id() + "]");
                op.invoke();
                if (Thread.currentThread().isInterrupted()) {
                    LOG.info("Interrupting composite operation");
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (InterruptedException e) {
                LOG.info("Interrupting composite operation");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.error(e.getMessage(), e);
            } finally {
                Thread.currentThread().setName(tn);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CompositeOperation that = (CompositeOperation) o;
        return Arrays.equals(operations, that.operations);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(operations);
    }

    @Override
    public String id() {
        return gid + ":" + id;
    }

    @Override
    public String toString() {
        return String.format("[%s:%s CompositeOperation] %s", gid, id, Arrays.asList(operations));
    }

}
