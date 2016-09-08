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
        pd.setPatchLocation(new File("target"));
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

    @Test
    public void persistPatchResultWithChildren() throws IOException {
        PatchData pd = new PatchData("otherid");
        pd.setPatchLocation(new File("target"));
        pd.setPatchLocation(new File("target"));
        PatchResult res = new PatchResult(pd, false, 42L, null, null);
        res.getVersions().add("1.0");
        res.getVersions().add("1.1");
        PatchResult resa = new PatchResult(pd, false, 42L, null, null, res);
        resa.getVersions().add("2.0");
        resa.getVersions().add("2.1");
        PatchResult resb = new PatchResult(pd, false, 42L, null, null, res);
        resb.getVersions().add("3.0");
        resb.getVersions().add("3.1");
        res.addChildResult("c1", resa);
        res.addChildResult("c2", resb);

        res.store();

        Properties props = new Properties();
        File result = new File("target/otherid.patch.result");
        props.load(new FileInputStream(result));
        assertThat(Integer.parseInt(props.getProperty("version.count")), equalTo(2));
        assertThat(Integer.parseInt(props.getProperty("update.count")), equalTo(0));

        PatchResult res2 = PatchResult.load(pd, props);
        assertThat(res2.getVersions().get(0), equalTo("1.0"));
        assertThat(res2.getChildPatches().get("c1").getVersions().get(0), equalTo("2.0"));
        assertThat(res2.getChildPatches().get("c2").getVersions().get(1), equalTo("3.1"));
        assertThat(res2.getChildPatches().get("c1").getParent(), equalTo(res2));
        assertThat(res2.getChildPatches().get("c2").getParent(), equalTo(res2));
    }

    @Test
    public void persistPatchResultOnWindows() throws IOException {
        PatchData pd = new PatchData("otherid");
        pd.setPatchLocation(new File("target"));
        PatchResult res = new PatchResult(pd, false, 42L, null, null);
        BundleUpdate bu = new BundleUpdate("C__Dev_jboss-fuse", null, null, "6.2.1", "wrap:jardir:C:\\Dev\\jboss-fuse-6.2.1.redhat-076\\etc\\auth$Bundle-SymbolicName=C:\\Dev\\jboss-fuse&Bundle-Version=6.2.1");
        res.getBundleUpdates().add(bu);

        File result = new File("target/patch-descriptor-3.patch.result");
        FileOutputStream out = new FileOutputStream(result);
        res.storeTo(out);

        PatchResult pr = PatchResult.load(pd, new FileInputStream(result));

        assertThat(pr.getBundleUpdates().get(0).getPreviousLocation(), equalTo("wrap:jardir:C:\\Dev\\jboss-fuse-6.2.1.redhat-076\\etc\\auth$Bundle-SymbolicName=C:\\Dev\\jboss-fuse&Bundle-Version=6.2.1"));
    }

}
