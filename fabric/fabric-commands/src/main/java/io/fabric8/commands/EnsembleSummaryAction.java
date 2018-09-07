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

import java.io.StringWriter;
import java.util.Map;

import io.fabric8.api.ZooKeeperClusterService;
import org.apache.felix.gogo.commands.Command;
import org.apache.karaf.shell.console.AbstractAction;

@Command(name = EnsembleSummary.FUNCTION_VALUE, scope = EnsembleSummary.SCOPE_VALUE, description = EnsembleSummary.DESCRIPTION)
public class EnsembleSummaryAction extends AbstractAction {

    private final ZooKeeperClusterService zooKeeperClusterService;

    public EnsembleSummaryAction(ZooKeeperClusterService zooKeeperClusterService) {
        this.zooKeeperClusterService = zooKeeperClusterService;
    }

    @Override
    protected Object doExecute() throws Exception {
        StringWriter containers = new StringWriter();
        if (zooKeeperClusterService.getEnsembleContainers() != null) {
            for (String container : zooKeeperClusterService.getEnsembleContainers()) {
                containers.append(", ").append(container);
            }
        }
        System.out.println("Ensemble URL: " + zooKeeperClusterService.getZooKeeperUrl());
        System.out.println("Ensemble containers: " + (containers.toString().length() > 2 ? containers.toString().substring(2)
                : "Can't find container names"));
        Map<String, String> configuration = zooKeeperClusterService.getEnsembleConfiguration();
        boolean saslEnabled = false;
        if (configuration != null) {
            saslEnabled = "true".equalsIgnoreCase(configuration.get("quorum.auth.enableSasl"));
        }
        System.out.println("SASL Peer authentication enabled: " + saslEnabled);

        return null;
    }

}
