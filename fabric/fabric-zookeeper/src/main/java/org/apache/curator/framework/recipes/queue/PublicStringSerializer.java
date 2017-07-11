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
package org.apache.curator.framework.recipes.queue;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reuse {@link ItemSerializer} but with public access
 */
public class PublicStringSerializer {

    private static QueueSerializer<String> INSTANCE = new StringSerializer();

    public static QueueSerializer<String> serializer() {
        return INSTANCE;
    }

    public static byte[] serialize(String item) throws Exception {
        final AtomicReference<String> ref = new AtomicReference<>(item);
        final MultiItem<String> multiItem = new MultiItem<String>()
        {
            @Override
            public String nextItem() throws Exception
            {
                return ref.getAndSet(null);
            }
        };
        return ItemSerializer.serialize(multiItem, INSTANCE);
    }

    public static String deserialize(byte[] bytes) throws Exception {
        MultiItem<String> items = ItemSerializer.deserialize(bytes, INSTANCE);
        return items.nextItem();
    }

    /**
     * Plain {@link String} &lt;-&gt <code>byte[]</code> {@link QueueSerializer}
     */
    private static class StringSerializer implements QueueSerializer<String> {
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
