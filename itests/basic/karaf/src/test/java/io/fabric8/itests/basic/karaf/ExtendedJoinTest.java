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
package io.fabric8.itests.basic.karaf;

import io.fabric8.api.Container;
import io.fabric8.api.FabricService;
import io.fabric8.api.gravia.ServiceLocator;
import io.fabric8.itests.support.CommandSupport;
import io.fabric8.itests.support.ContainerBuilder;
import io.fabric8.itests.support.EnsembleSupport;
import io.fabric8.itests.support.ProvisionSupport;
import io.fabric8.itests.support.ServiceProxy;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.gogo.commands.basic.AbstractCommand;
import org.apache.karaf.admin.AdminService;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.osgi.StartLevelAware;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;

@RunWith(Arquillian.class)
public class ExtendedJoinTest {

    private static final String WAIT_FOR_JOIN_SERVICE = "wait-for-service io.fabric8.zookeeper.bootstrap.BootstrapConfiguration";

    @Deployment
    @StartLevelAware(autostart = true)
    public static Archive<?> deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "extended-join-test.jar");
        archive.addPackage(CommandSupport.class.getPackage());
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleVersion("1.0.0");
                builder.addImportPackages(ServiceLocator.class, FabricService.class);
                builder.addImportPackages(AbstractCommand.class, Action.class);
                builder.addImportPackage("org.apache.felix.service.command;status=provisional");
                builder.addImportPackages(ConfigurationAdmin.class, AdminService.class, ServiceTracker.class, Logger.class);
                return builder.openStream();
            }
        });
        return archive;
    }

	/**
	 * This is a test for FABRIC-353.
	 */
	@Test
	@Ignore
	public void testJoinAndAddToEnsemble() throws Exception {
        System.err.println(CommandSupport.executeCommand("fabric:create --force --clean -n --wait-for-provisioning"));
        //System.out.println(executeCommand("shell:info"));
        //System.out.println(executeCommand("fabric:info"));
        //System.out.println(executeCommand("fabric:profile-list"));

        BundleContext moduleContext = ServiceLocator.getSystemContext();
        ServiceProxy<FabricService> fabricProxy = ServiceProxy.createServiceProxy(moduleContext, FabricService.class);
        try {
            FabricService fabricService = fabricProxy.getService();
            AdminService adminService = ServiceLocator.awaitService(AdminService.class);
            String version = System.getProperty("fabric.version");
            System.out.println(CommandSupport.executeCommand("admin:create --featureURL mvn:io.fabric8/fabric8-karaf/" + version + "/xml/features --feature fabric-git --feature fabric-agent --feature fabric-boot-commands basic_cnt_f"));
            System.out.println(CommandSupport.executeCommand("admin:create --featureURL mvn:io.fabric8/fabric8-karaf/" + version + "/xml/features --feature fabric-git --feature fabric-agent --feature fabric-boot-commands basic_cnt_g"));
            try {
                System.out.println(CommandSupport.executeCommand("admin:start basic_cnt_f"));
                System.out.println(CommandSupport.executeCommand("admin:start basic_cnt_g"));
                ProvisionSupport.instanceStarted(Arrays.asList("basic_cnt_f", "basic_cnt_g"), ProvisionSupport.PROVISION_TIMEOUT);
                System.out.println(CommandSupport.executeCommand("admin:list"));
                String joinCommand = "fabric:join -f --zookeeper-password "+ fabricService.getZookeeperPassword() +" " + fabricService.getZookeeperUrl();

                String response = "";
                for (int i = 0; i < 10 && !response.contains("true"); i++) {
                    response = CommandSupport.executeCommand("ssh:ssh -l karaf -P karaf -p " + adminService.getInstance("basic_cnt_f").getSshPort() + " localhost " + WAIT_FOR_JOIN_SERVICE);
                    Thread.sleep(1000);
                }
                response = "";
                for (int i = 0; i < 10 && !response.contains("true"); i++) {
                    response = CommandSupport.executeCommand("ssh:ssh -l karaf -P karaf -p " + adminService.getInstance("basic_cnt_g").getSshPort() + " localhost " + WAIT_FOR_JOIN_SERVICE);
                    Thread.sleep(1000);
                }

                System.err.println(CommandSupport.executeCommand("ssh:ssh -l karaf -P karaf -p " + adminService.getInstance("basic_cnt_f").getSshPort() + " localhost " + joinCommand));
                System.err.println(CommandSupport.executeCommand("ssh:ssh -l karaf -P karaf -p " + adminService.getInstance("basic_cnt_g").getSshPort() + " localhost " + joinCommand));
                ProvisionSupport.containersExist(Arrays.asList("basic_cnt_f", "basic_cnt_g"), ProvisionSupport.PROVISION_TIMEOUT);
                Container cntF = fabricService.getContainer("basic_cnt_f");
                Container cntG = fabricService.getContainer("basic_cnt_g");
                ProvisionSupport.containerStatus(Arrays.asList(cntF, cntG), "success", ProvisionSupport.PROVISION_TIMEOUT);
                EnsembleSupport.addToEnsemble(fabricService, cntF, cntG);
                System.out.println(CommandSupport.executeCommand("fabric:container-list"));
                EnsembleSupport.removeFromEnsemble(fabricService, cntF, cntG);
                System.out.println(CommandSupport.executeCommand("fabric:container-list"));
            } finally {
                System.out.println(CommandSupport.executeCommand("admin:stop basic_cnt_f"));
                System.out.println(CommandSupport.executeCommand("admin:stop basic_cnt_g"));
            }
        } finally {
            fabricProxy.close();
        }
	}

    /**
     * Verify that containers can use dot symbol in their name
     * 
     * @throws Exception
     */
    @Test
    public void testContainerHostnameSupport() throws Exception {
        System.out.println(CommandSupport.executeCommand("fabric:create --force --clean -n"));
        //System.out.println(executeCommand("shell:info"));
        //System.out.println(executeCommand("fabric:info"));
        //System.out.println(executeCommand("fabric:profile-list"));



        BundleContext moduleContext = ServiceLocator.getSystemContext();
        ServiceProxy<FabricService> fabricProxy = ServiceProxy.createServiceProxy(moduleContext, FabricService.class);
        try {
            FabricService fabricService = fabricProxy.getService();
            AdminService adminService = ServiceLocator.awaitService(AdminService.class);
            Container root = fabricService.getContainer("root");
            Set<Container> set = new HashSet<>();
            set.add(root);

            String version = System.getProperty("fabric.version");

            try {
                System.out.println(CommandSupport.executeCommand("fabric:profile-edit --features admin default"));
                ProvisionSupport.provisioningSuccess(set, 60_000L);
                System.out.println(CommandSupport.executeCommand("admin:create --java-opts ' -Dpatching.disabled=true ' --featureURL mvn:io.fabric8/fabric8-karaf/" + version + "/xml/features --feature fabric joiner "));
                System.out.println(CommandSupport.executeCommand("admin:start joiner"));
                ProvisionSupport.instanceStarted(Arrays.asList("joiner"), ProvisionSupport.PROVISION_TIMEOUT);

                System.out.println(CommandSupport.executeCommand("admin:list"));
                String joinCommand = "fabric:join -f --zookeeper-password "+ fabricService.getZookeeperPassword() +" " + fabricService.getZookeeperUrl() + " joiner.complex.name";

                String response = "";
                for (int i = 0; i < 10 && !response.contains("true"); i++) {
                    response = CommandSupport.executeCommand("ssh:ssh -l karaf -P karaf -p " + adminService.getInstance("joiner").getSshPort() + " localhost " + WAIT_FOR_JOIN_SERVICE);
                    Thread.sleep(1000);
                }
                response = CommandSupport.executeCommand("ssh:ssh -l karaf -P karaf -p " + adminService.getInstance("joiner").getSshPort() + " localhost " + joinCommand);

                ProvisionSupport.containersExist(Arrays.asList("joiner.complex.name"), ProvisionSupport.PROVISION_TIMEOUT);
                Container joiner = fabricService.getContainer("joiner.complex.name");
                ProvisionSupport.containerStatus(Arrays.asList(joiner), "success", ProvisionSupport.PROVISION_TIMEOUT);


                //System.err.println(CommandSupport.executeCommand("ssh:ssh -l karaf -P karaf -p " + adminService.getInstance("basic_cnt_f").getSshPort() + " localhost " + joinCommand));

            } finally {
                System.out.println(CommandSupport.executeCommand("fabric:container-info joiner"));
                System.out.println(CommandSupport.executeCommand("admin:stop joiner"));
            }
        } finally {
            fabricProxy.close();
        }
    }
}
