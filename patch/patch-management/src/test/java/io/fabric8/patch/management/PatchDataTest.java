/**
 *  Copyright 2005-2015 Red Hat, Inc.
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
package io.fabric8.patch.management;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class PatchDataTest {

    @Test
    public void readPatchDataFromDescriptor() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("id = myid\n");
        sb.append("bundle.0 = io/fabric8/fabric-api/1.0/fabric-api-1.0.jar\n");
        sb.append("bundle.1 = io/fabric8/fabric-web/1.0/fabric-web-1.0.war\n");
        sb.append("bundle.count = 2");
        File patchFile = new File("target/patch-descriptor-1.patch");
        FileUtils.write(patchFile, sb.toString());
        Properties props = new Properties();
        props.load(new FileInputStream(patchFile));
        PatchData data = PatchData.load(props);
        assertThat(data.getId(), equalTo("myid"));
        assertThat(data.getBundles().size(), equalTo(2));
    }

    @Test
    public void persistPatchData() throws IOException {
        PatchData pd = new PatchData("otherid");
        pd.getBundles().add("bundle1");
        pd.getBundles().add("bundle2");
        pd.getBundles().add("bundle3");
        pd.getOtherArtifacts().add("other1");
        File patchFile = new File("target/patch-descriptor-2.patch");
        FileOutputStream out = new FileOutputStream(patchFile);
        pd.storeTo(out);

        Properties props = new Properties();
        props.load(new FileInputStream(patchFile));
        assertThat(Integer.parseInt(props.getProperty("bundle.count")), equalTo(3));
        assertThat(Integer.parseInt(props.getProperty("artifact.count")), equalTo(1));
        assertThat(props.getProperty("id"), equalTo("otherid"));
    }

    @Test
    public void persistPatchResult() throws IOException {
        PatchData pd = new PatchData("otherid");
        PatchResult res = new PatchResult(pd, false, 42L, null, null);
        res.getVersions().add("1.0");
        res.getVersions().add("1.1");

        File result = new File("target/patch-descriptor-2.patch.result");
        FileOutputStream out = new FileOutputStream(result);
        res.storeTo(out);

        Properties props = new Properties();
        props.load(new FileInputStream(result));
        assertThat(Integer.parseInt(props.getProperty("version.count")), equalTo(2));
        assertThat(Integer.parseInt(props.getProperty("update.count")), equalTo(0));

        PatchResult res2 = PatchResult.load(pd, props);
        assertThat(res.getVersions().get(0), equalTo("1.0"));
    }

}
