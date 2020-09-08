/*
 *  Copyright 2005-2020 Red Hat, Inc.
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
package io.fabric8.service.ssh;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;

import org.apache.commons.codec.binary.Base64;
import org.junit.Ignore;
import org.junit.Test;

public class DeserializationTest {

    @Test
    @Ignore("Unignore to test metadata from ZK")
    public void deserializeMetadata() throws Exception {
        String s = "<base64 data here>";
        byte[] bytes = Base64.decodeBase64(s);
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
        Object o = ois.readObject();
        System.out.println(o);
    }

}
