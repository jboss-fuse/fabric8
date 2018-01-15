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
package io.fabric8.zookeeper.utils;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class InterpolationHelperTest {

    InterpolationHelper.SubstitutionCallback dummyCallback;

    @Before
    public void init() throws Exception {
        dummyCallback = new InterpolationHelper.SubstitutionCallback() {
            public String getValue(String key) {
                return null;
            }
        };
    }

    @Test
    public void testSubstVars() throws Exception {
        String val = "#${propName}#";
        String currentKey = "";
        Map<String, String> cycleMap = new HashMap<>();
        Map<String, String> configProps = new HashMap<>();
        configProps.put("propName", "OK");
        InterpolationHelper.SubstitutionCallback callback = this.dummyCallback;

        String result = InterpolationHelper.substVars(val, currentKey, cycleMap, configProps, callback);

        assertThat(result, equalTo("#OK#"));

        val = "#${ANOTHERpropName}#";
        result = InterpolationHelper.substVars(val, currentKey, cycleMap, configProps, callback);
        assertThat(result, equalTo("##"));
    }

    @Test
    public void testSubstVarsPreserveUnresolved() throws Exception {
        String val = "#${propName}#";
        String currentKey = "";
        Map<String, String> cycleMap = new HashMap<>();
        Map<String, String> configProps = new HashMap<>();
        configProps.put("propName", "OK");
        InterpolationHelper.SubstitutionCallback callback = this.dummyCallback;

        String result = InterpolationHelper.substVarsPreserveUnresolved(val, currentKey, cycleMap, configProps, callback);

        assertThat(result, equalTo("#OK#"));

        val = "#${ANOTHERpropName}#";
        result = InterpolationHelper.substVarsPreserveUnresolved(val, currentKey, cycleMap, configProps, callback);
        assertThat(result, equalTo(val));
    }

    @Test
    public void testEscaping() {
        Hashtable<String, Object> props = new Hashtable<>();
        props.put("a", "abcd");
        props.put("b", "abcd{");
        props.put("c", "abcd${");
        props.put("d", "abc}d");
        props.put("e", "{abcd}");
        props.put("f", "${abcd}");
        props.put("g", "$\\{abcd\\}");
        props.put("h", "${abcd{}}");
        props.put("i", "${abcd${x}}");
        props.put("j", "abcd$e{");
        InterpolationHelper.escapePropertyPlaceholders(props);

        assertThat(props.get("a").toString(), equalTo("abcd"));
        assertThat(props.get("b").toString(), equalTo("abcd{"));
        assertThat(props.get("c").toString(), equalTo("abcd$\\{"));
        assertThat(props.get("d").toString(), equalTo("abc}d"));
        assertThat(props.get("e").toString(), equalTo("{abcd}"));
        assertThat(props.get("f").toString(), equalTo("$\\{abcd\\}"));
        assertThat(props.get("g").toString(), equalTo("$\\{abcd\\}"));
        assertThat(props.get("h").toString(), equalTo("$\\{abcd{}\\}"));
        assertThat(props.get("i").toString(), equalTo("$\\{abcd$\\{x\\}\\}"));
        assertThat(props.get("j").toString(), equalTo("abcd$e{"));
    }

}
