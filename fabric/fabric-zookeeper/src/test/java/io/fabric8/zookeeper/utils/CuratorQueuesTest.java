/*
 *  Copyright 2005-2017 Red Hat, Inc.
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

import java.io.UnsupportedEncodingException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.DistributedQueue;
import org.apache.curator.framework.recipes.queue.QueueBuilder;
import org.apache.curator.framework.recipes.queue.QueueConsumer;
import org.apache.curator.framework.recipes.queue.QueueSerializer;
import org.apache.curator.framework.state.ConnectionState;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class CuratorQueuesTest extends ZookeeperServerTestSupport {

    @Test
    public void queues() throws Exception {
        String reqPath = "/fabric/registry/containers/commands/root/request";
        String resPath = "/fabric/registry/containers/commands/root/response";
        Serializer serializer = new Serializer();

        final DistributedQueue<String> req = QueueBuilder.builder(curator, null, serializer, reqPath).buildQueue();
        req.start();
        DistributedQueue<String> res = QueueBuilder.builder(curator, null, serializer, resPath).buildQueue();
        res.start();

        req.put("{ id: 1 }");
        req.put("{ id: 3 }");

        System.out.println("=== Before taking items ===");
        dump("/fabric/registry/containers");

        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final DistributedQueue<String> res2 = QueueBuilder.builder(curator, new QueueConsumer<String>() {
            @Override
            public void consumeMessage(String message) throws Exception {
                queue.offer(message);
            }

            @Override
            public void stateChanged(CuratorFramework client, ConnectionState newState) {
            }
        }, serializer, resPath).buildQueue();
        DistributedQueue<String> req2 = QueueBuilder.builder(curator, new QueueConsumer<String>() {
            @Override
            public void consumeMessage(String message) throws Exception {
                res2.put(message + "!");
            }

            @Override
            public void stateChanged(CuratorFramework client, ConnectionState newState) {
            }
        }, serializer, reqPath).buildQueue();
        req2.start();
        res2.start();

        assertThat(queue.take(), equalTo("{ id: 1 }!"));
        assertThat(queue.take(), equalTo("{ id: 3 }!"));
        assertThat(queue.poll(420, TimeUnit.MILLISECONDS), nullValue());

        System.out.println("=== After taking items ===");
        dump("/fabric/registry/containers");

        req.close();
        res.close();
    }

    @Test
    public void limitedQueues() throws Exception {
        String reqPath = "/fabric/registry/containers/commands/root/request";
        Serializer serializer = new Serializer();

        final DistributedQueue<String> req = QueueBuilder.builder(curator, null, serializer, reqPath)
                .maxItems(6)
                .buildQueue();
        req.start();

        for (int i = 0; i < 12; i++) {
            req.put(String.format("{ id: %d }", i + 1), 100, TimeUnit.MILLISECONDS);
        }

        System.out.println("=== Before taking items ===");
        dump("/fabric/registry/containers");

        req.close();
    }

    @Test
    public void scheduledExecutors() throws InterruptedException {
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);

        final List<String> results = new LinkedList<>();
        pool.schedule(new Runnable() {
            @Override
            public void run() {
                results.add("1");
            }
        }, 1L, TimeUnit.SECONDS);

        pool.schedule(new Runnable() {
            @Override
            public void run() {
                results.add("2");
            }
        }, 100L, TimeUnit.MILLISECONDS);

        pool.shutdown();
        pool.awaitTermination(2L, TimeUnit.SECONDS);

        assertThat(results.get(0), equalTo("2"));
        assertThat(results.get(1), equalTo("1"));
    }

    private static class Serializer implements QueueSerializer<String> {

        @Override
        public byte[] serialize(String item) {
            try {
                return item == null ? new byte[0] : item.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        @Override
        public String deserialize(byte[] bytes) {
            try {
                return bytes == null ? "" : new String(bytes, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

    }

}
