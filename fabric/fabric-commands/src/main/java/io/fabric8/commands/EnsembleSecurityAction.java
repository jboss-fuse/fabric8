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
package io.fabric8.commands;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.fabric8.api.DataStore;
import io.fabric8.api.FabricService;
import io.fabric8.api.Profile;
import io.fabric8.api.ProfileBuilder;
import io.fabric8.api.ProfileService;
import io.fabric8.api.ZooKeeperClusterService;
import io.fabric8.utils.shell.ShellUtils;
import io.fabric8.zookeeper.ZkPath;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.karaf.shell.console.AbstractAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getStringData;

@Command(name = EnsembleSecurity.FUNCTION_VALUE, scope = EnsembleSecurity.SCOPE_VALUE, description = EnsembleSecurity.DESCRIPTION)
public class EnsembleSecurityAction extends AbstractAction {

    public static Logger LOG = LoggerFactory.getLogger(EnsembleSecurityAction.class);

    private final ZooKeeperClusterService zooKeeperClusterService;
    private final ProfileService profileService;
    private final FabricService fabricService;

    @Option(name = "--enable-sasl", multiValued = false, required = false, description = "Enables SASL/DIGEST-MD5 mutual peer authentication.")
    protected Boolean enable = null;

    @Option(name = "--disable-sasl", multiValued = false, required = false, description = "Disables SASL/DIGEST-MD5 mutual peer authentication.")
    protected Boolean disable = null;

    public EnsembleSecurityAction(FabricService fabricService, ZooKeeperClusterService zooKeeperClusterService, ProfileService profileService) {
        this.fabricService = fabricService;
        this.zooKeeperClusterService = zooKeeperClusterService;
        this.profileService = profileService;
    }

    @Override
    protected Object doExecute() throws Exception {
        if (enable != null && disable != null) {
            System.out.println("Please specify whether to disable or enable SASL/DIGEST-MD5 mutual peer authentication");
            return null;
        }

        Map<String, String> configuration = zooKeeperClusterService.getEnsembleConfiguration();
        EnsembleSecurity.EnsembleSASL saslStatus = EnsembleSecurity.isSASLEnabled(configuration);

        if (enable == null && disable == null) {
            if (saslStatus == EnsembleSecurity.EnsembleSASL.NO_QUORUM) {
                System.out.println("Zookeeper works in single server mode");
            } else {
                System.out.println("SASL/DIGEST-MD5 mutual peer authentication is currently "
                        + (saslStatus == EnsembleSecurity.EnsembleSASL.ENABLED ? "enabled" : "disabled"));
            }
            return null;
        }

        if (saslStatus == EnsembleSecurity.EnsembleSASL.NO_QUORUM) {
            System.out.println("Can't configure SASL/DIGEST-MD5 mutual peer authentication - Zookeeper works in single server mode");
            return null;
        }

        if (Boolean.TRUE.equals(enable) && saslStatus == EnsembleSecurity.EnsembleSASL.ENABLED) {
            System.out.println("SASL/DIGEST-MD5 mutual peer authentication is already enabled");
            return null;
        }

        if (Boolean.TRUE.equals(disable) && saslStatus == EnsembleSecurity.EnsembleSASL.DISABLED) {
            System.out.println("SASL/DIGEST-MD5 mutual peer authentication is already disabled");
            return null;
        }

        boolean enabled = Boolean.TRUE.equals(enable);

        String response = ShellUtils.readLine(session, "This will "
                + (enabled ? "enable" : "disable")
                + " mutual QuorumPeer authentication using SASL/DIGEST-MD5 mechanism.\n" +
                "It is recommended to backup data/git and data/zookeeper directories.\n" +
                "During the process, Zookeeper connection may be suspended and resumed several times.\n" +
                "Are you sure want to proceed? (yes/no): ", false);
        if (response == null || !(response.toLowerCase().equals("yes") || response.toLowerCase().equals("y"))) {
            return null;
        }

        // see: io.fabric8.git.internal.GitDataStoreImpl.VersionCacheLoader.loadVersion()
        // both master branch and version branch profiles are loaded into given version.
        try {
            CuratorFramework curator = fabricService.adapt(CuratorFramework.class);
            DataStore dataStore = fabricService.adapt(DataStore.class);
            String clusterId = getStringData(curator, ZkPath.CONFIG_ENSEMBLES.getPath());

            String versionId = dataStore.getDefaultVersion();
            String profileId = "fabric-ensemble-" + clusterId;
            Profile ensembleProfile = profileService.getRequiredProfile(versionId, profileId);

            ProfileBuilder builder = ProfileBuilder.Factory.createFrom(ensembleProfile);
            Map<String, String> properties = builder.getConfiguration("io.fabric8.zookeeper.server-" + clusterId);
            properties.put("quorum.auth.enableSasl", Boolean.toString(enabled));
            builder.addConfiguration("io.fabric8.zookeeper.server-" + clusterId, properties);

            final AtomicBoolean change = new AtomicBoolean(false);

            ConnectionStateListener csl = new ConnectionStateListener() {
                @Override
                public void stateChanged(CuratorFramework curatorFramework, ConnectionState connectionState) {
                    System.out.println("Zookeeper connection state changed to: " + connectionState.name());
                    change.set(true);
                }
            };
            curator.getConnectionStateListenable().addListener(csl);

            try {
                LOG.info((enabled ? "Enabling" : "Disabling") + " SASL/DIGEST-MD5 mutual peer authentication");
                profileService.updateProfile(builder.getProfile());

                // in 5 seconds will start checking if there was a change
                // we'll be checking for a change for 1 minute - if there's no change, we'll stop
                Thread.sleep(5000);
                int count = 12;
                while (count > 0) {
                    try {
                        if (change.compareAndSet(false, false)) {
                            break;
                        }
                        change.set(false);
                        LOG.info("Monitoring Zookeeper connection state change");
                        Thread.sleep(5000);
                        count--;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                System.out.println("Ensemble security configuration changed.");
            } finally {
                curator.getConnectionStateListenable().removeListener(csl);
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            System.err.println("Problem during ensemble security configuration (please check log for details): "
                    + e.getMessage());
        }

        return null;
    }

}
