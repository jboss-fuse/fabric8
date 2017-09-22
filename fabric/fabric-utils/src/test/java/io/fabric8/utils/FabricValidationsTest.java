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
package io.fabric8.utils;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;

import org.junit.Test;

import static io.fabric8.utils.FabricValidations.isValidContainerName;
import static io.fabric8.utils.FabricValidations.isValidProfileName;
import static io.fabric8.utils.FabricValidations.validateProfileName;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static io.fabric8.utils.FabricValidations.validateContainerName;
import static io.fabric8.utils.FabricValidations.isURIValid;

public class FabricValidationsTest {

    @Test(expected = IllegalArgumentException.class)
    public void testContainerWithInvalidPrefix() {
        validateContainerName("--container");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testContainerWithInvalidPrefix2() {
        validateContainerName("_container");
    }

    public void testContainerWithUpperCase() {
        validateContainerName("MyContainer");
    }

    @Test
    public void testValidContainerNames() {
        assertTrue(isValidContainerName("c"));
        assertTrue(isValidContainerName("c1"));
        assertTrue(isValidContainerName("c-1"));
        assertTrue(isValidContainerName("c_1"));
        assertTrue(isValidContainerName("1container"));
        assertTrue(isValidContainerName("container1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProfileWithInvalidPrefix() {
        validateProfileName("--profile");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testProfileWithInvalidPrefix2() {
        validateProfileName("_profile");
    }

    @Test
    public void testValidProfileNames() {
        assertTrue(isValidProfileName("c"));
        assertTrue(isValidProfileName("c1"));
        assertTrue(isValidProfileName("c-1"));
        assertTrue(isValidProfileName("c_1"));
        assertTrue(isValidProfileName("1container"));
        assertTrue(isValidProfileName("container1"));

        assertTrue(isValidProfileName("c"));
        assertTrue(isValidProfileName("c1"));
        assertTrue(isValidProfileName("c-1"));
        assertTrue(isValidProfileName("c_1"));
        assertTrue(isValidProfileName("1container"));
        assertTrue(isValidProfileName("container1"));
        assertTrue(isValidProfileName("Container1"));

        // we also allow dots
        assertTrue(isValidProfileName("my.container.name"));
        assertTrue(isValidProfileName("my.container123.name"));
    }

    @Test
    public void uris() throws URISyntaxException {
        assertFalse(isURIValid("http:///path"));
        assertFalse(isURIValid("http://:/path"));
        assertFalse(isURIValid("http://:8181/path"));
        assertFalse(isURIValid("http://:abcd/path"));
        assertFalse(isURIValid("http://u:p/path"));
        assertFalse(isURIValid("http://u:p@/path"));
        assertFalse(isURIValid("http://u:p@:/path"));
        assertFalse(isURIValid("http://u:p@:8181/path"));
        assertFalse(isURIValid("http://u:p@:abcd/path"));
        assertFalse(isURIValid("http://host.name:abcd/path"));
        assertFalse(isURIValid("http://u:p@host.name:abcd/path"));
        assertTrue(isURIValid("http://host.name/path"));
        assertTrue(isURIValid("http://host.name:8181/path"));
        assertTrue(isURIValid("http://u@host.name:8181/path"));
        assertTrue(isURIValid("http://u:p@host.name:8181/path"));
        // ENTESB-7303
        assertTrue(isURIValid(new File("/tmp/x").toURI().toString()));
        // authority (user:password@host:port) == null, path == //tmp/x
        assertTrue(isURIValid("file:////tmp/x"));
        assertThat(new URI("file:////tmp/x").getAuthority(), nullValue());
        assertThat(new URI("file:////tmp/x").getPath(), is("//tmp/x"));
        // authority (user:password@host:port) == null, path == /tmp/x
        assertTrue(isURIValid("file:///tmp/x"));
        assertThat(new URI("file:///tmp/x").getAuthority(), nullValue());
        assertThat(new URI("file:///tmp/x").getPath(), is("/tmp/x"));
        // authority (user:password@host:port) == tmp,  path == /x
        assertTrue(isURIValid("file://tmp/x"));
        assertThat(new URI("file://tmp/x").getAuthority(), is("tmp"));
        assertThat(new URI("file://tmp/x").getPath(), is("/x"));
        // authority (user:password@host:port) == null, path == /tmp/x
        // https://stackoverflow.com/a/17870390/250517
        assertTrue(isURIValid("file:/tmp/x"));
        assertThat(new URI("file:/tmp/x").getAuthority(), nullValue());
        assertThat(new URI("file:/tmp/x").getPath(), is("/tmp/x"));
    }

}
