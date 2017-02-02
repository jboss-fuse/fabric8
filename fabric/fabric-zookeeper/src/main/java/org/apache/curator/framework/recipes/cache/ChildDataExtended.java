/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.curator.framework.recipes.cache;

import org.apache.zookeeper.data.Stat;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public class ChildDataExtended extends ChildData
{
    private final AtomicReference<byte[]>    data;

    public ChildDataExtended(String path, Stat stat, byte[] data)
    {
        super(path, stat, null);
        this.data = new AtomicReference<byte[]>(data);
    }

    @SuppressWarnings("RedundantIfStatement")
    @Override
    public boolean equals(Object o)
    {
        if ( !super.equals(o) )
        {
            return false;
        }

        ChildDataExtended childData = (ChildDataExtended)o;

        if ( !Arrays.equals(data.get(), childData.data.get()) )
        {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode()
    {
        int result = getPath() != null ? getPath().hashCode() : 0;
        result = 31 * result + (getStat() != null ? getStat().hashCode() : 0);
        result = 31 * result + (data != null ? Arrays.hashCode(data.get()) : 0);
        return result;
    }

    /**
     * <p>Returns the node data for this child when the cache mode is set to cache data.</p>
     *
     * <p><b>NOTE:</b> the byte array returned is the raw reference of this instance's field. If you change
     * the values in the array any other callers to this method will see the change.</p>
     *
     * @return node data or null
     */
    public byte[] getData()
    {
        return data.get();
    }

    void clearData()
    {
        data.set(null);
    }

    @Override
    public String toString()
    {
        return "ChildDataExtended{" +
            "path='" + getPath() + '\'' +
            ", stat=" + getStat() +
            ", data=" + Arrays.toString(data.get()) +
            '}';
    }
}
