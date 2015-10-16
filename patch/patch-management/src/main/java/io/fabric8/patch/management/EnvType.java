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

public enum EnvType {
    /**
     * No fabric
     */
    STANDALONE(false, "baseline-%s", "baseline-%s", "fuse"),
//    /**
//     * Karaf child container (<code>admin:create</code>)
//     */
//    STANDALONE_CHILD(false, null, null),

    /**
     * Fabric container (SSH or root) based on Fuse distro
     */
    FABRIC_FUSE(true, "foundation-root-fuse-%s", "baseline-root-fuse-%s", "fuse"),
    /**
     * SSH container based on fabric8-karaf distro
     */
    FABRIC_FABRIC8(true, "foundation-ssh-fabric8-%s", "baseline-ssh-fabric8-%s", "fabric"),
    /**
     * Fabric container (SSH or root) based on AMQ distro
     */
    FABRIC_AMQ(true, "foundation-root-amq-%s", "baseline-root-amq-%s", "fuse"),
    /**
     * Child container created in fabric env
     */
    FABRIC_CHILD(true, "foundation-child-%s", "baseline-child-%s", "karaf"),

    /**
     * Openshift? JClouds?
     */
    UNKNOWN(false, null, null, null);

    private boolean fabric;

    /**
     * Fabric mode: Pattern for a tag that contains the state of container at particular version. Each container's
     * private history branch starts from one of such baselines.
     */
    private String baselineTagFormat;
    /**
     * Fabric mode: Pattern for a tag that's placed on container's private history branch. If history branch doesn't
     * contain such tag, it must be rebased (during fabric-agent provisioning)
     */
    private String historyTagFormat;
    private String productId;

    EnvType(boolean fabric, String historyTagFormat, String baselineTagFormat, String productId) {
        this.fabric = fabric;
        this.historyTagFormat = historyTagFormat;
        this.baselineTagFormat = baselineTagFormat;
        this.productId = productId;
    }

    public boolean isFabric() {
        return fabric;
    }

    public String getBaselineTagFormat() {
        return baselineTagFormat;
    }

    public String getHistoryTagFormat() {
        return historyTagFormat;
    }

    public String getProductId() {
        return productId;
    }

}
