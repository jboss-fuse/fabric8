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
package io.fabric8.git.http;

import io.fabric8.api.Constants;
import io.fabric8.api.FabricService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.git.GitDataStore;
import io.fabric8.git.GitHttpEndpoint;
import io.fabric8.git.GitNode;
import io.fabric8.git.jmx.GitHttpEndpointMBean;
import io.fabric8.groups.Group;
import io.fabric8.groups.GroupListener;
import io.fabric8.groups.internal.ZooKeeperGroup;
import io.fabric8.utils.NamedThreadFactory;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
@Component(name = "io.fabric8.git.server", label = "Fabric8 Git HTTP Server Registration Handler", policy = ConfigurationPolicy.OPTIONAL, immediate = true, metatype = true)
@Service(GitHttpEndpoint.class)
public final class GitHttpServerRegistrationHandler extends AbstractComponent implements GitHttpEndpoint, GroupListener<GitNode> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHttpServerRegistrationHandler.class);

    private static final String REALM_PROPERTY_NAME = "realm";
    private static final String ROLE_PROPERTY_NAME = "role";
    private static final String DEFAULT_ROLE = "admin";
    private static final String DEFAULT_REALM = "karaf";

    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configAdmin = new ValidatingReference<ConfigurationAdmin>();
    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();
    @Reference(referenceInterface = HttpService.class)
    private final ValidatingReference<HttpService> httpService = new ValidatingReference<HttpService>();
    @Reference(referenceInterface = GitDataStore.class)
    private final ValidatingReference<GitDataStore> gitDataStore = new ValidatingReference<>();
    @Reference(referenceInterface = RuntimeProperties.class)
    private final ValidatingReference<RuntimeProperties> runtimeProperties = new ValidatingReference<RuntimeProperties>();
    @Reference(referenceInterface = MBeanServer.class, bind = "bindMBeanServer", unbind = "unbindMBeanServer")
    private final ValidatingReference<MBeanServer> mbeanServer = new ValidatingReference<MBeanServer>();

    //Reference not used, but it expresses the dependency on a fully initialized fabric.
    @Reference
    private FabricService fabricService;

    final AtomicBoolean isMaster = new AtomicBoolean();
    private final AtomicReference<String> gitRemoteUrl = new AtomicReference<>();
    private Group<GitNode> group;
    private Path basePath;
    Git git;

    private String realm;
    private String role;
    private Path dataPath;

    private ObjectName objectName;

    public GitHttpServerRegistrationHandler() {
        try {
            objectName = new ObjectName("io.fabric8:type=GitServer");
        } catch (MalformedObjectNameException ignored) {
        }
    }

    @Activate
    void activate(Map<String, ?> configuration) throws Exception {
        RuntimeProperties sysprops = runtimeProperties.get();
        realm = getConfiguredRealm(sysprops, configuration);
        role = getConfiguredRole(sysprops, configuration);
        dataPath = sysprops.getDataPath();
        activateComponent();

        group = new ZooKeeperGroup<GitNode>(curator.get(), ZkPath.GIT.getPath(), GitNode.class, new NamedThreadFactory("zkgroup-git-httpreg"));
        //if anything went wrong in a previous deactivation we still have to clean up the registry
        zkCleanUp(group);

        group.add(this);
        group.update(createState());
        group.start();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
        unregisterServlet();
        try {
            if (group != null) {
                group.close();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to remove git server from registry.", e);
        }
    }

    @Override
    public void groupEvent(Group<GitNode> group, GroupEvent event) {
        if (isValid()) {
            switch (event) {
            case CONNECTED:
            case CHANGED:
                updateMasterUrl(group);
                break;
            default:
                // do nothing
            }
        }
    }

    private String getConfiguredRealm(RuntimeProperties sysprops, Map<String, ?> configuration) {
        String realm = (String)configuration.get(REALM_PROPERTY_NAME);
        if (realm == null) {
            realm = DEFAULT_REALM;
        }
        return realm;
    }

    private String getConfiguredRole(RuntimeProperties sysprops, Map<String, ?> configuration) {
        return configuration.containsKey(ROLE_PROPERTY_NAME) ? (String)configuration.get(ROLE_PROPERTY_NAME) : DEFAULT_ROLE;
    }

    private void updateMasterUrl(Group<GitNode> group) {
        try {
            if (group.isMaster()) {
                LOGGER.debug("Git repo is the master");
                if (!isMaster.getAndSet(true)) {
                    registerServlet(dataPath, realm, role);
                }
            } else {
                LOGGER.debug("Git repo is not the master");
                if (isMaster.getAndSet(false)) {
                    unregisterServlet();
                }
            }

            GitNode state = createState();
            group.update(state);
            String url = state.getUrl();
            gitRemoteUrl.set(ZooKeeperUtils.getSubstitutedData(curator.get(), url));
        } catch (Exception e) {
            LOGGER.debug("Failed to update master git server url.", e);
        }
    }

    private void registerServlet(Path dataPath, String realm, String role) throws Exception {
        synchronized (gitRemoteUrl) {
            basePath = dataPath.resolve(Paths.get("git", "servlet"));
            Path fabricRepoPath = basePath.resolve("fabric");
            String servletBase = basePath.toFile().getAbsolutePath();

            // Init and clone the local repo.
            File fabricRoot = fabricRepoPath.toFile();

            if (!fabricRoot.exists()) {
                LOGGER.info("Cloning master root repo into {}", fabricRoot);
                File localRepo = gitDataStore.get().getGit().getRepository().getDirectory();
                git = Git.cloneRepository()
                    .setTimeout(10)
                    .setBare(true)
                    .setNoCheckout(true)
                    .setCloneAllBranches(true)
                    .setDirectory(fabricRoot)
                    .setURI(localRepo.toURI().toString())
                    .call();
            } else {
                LOGGER.info("{} already exists", fabricRoot);
                git = Git.open(fabricRoot);
            }

            HttpContext base = httpService.get().createDefaultHttpContext();
            HttpContext secure = new GitSecureHttpContext(base, curator.get(), realm, role);

            Dictionary<String, Object> initParams = new Hashtable<String, Object>();
            initParams.put("base-path", servletBase);
            initParams.put("repository-root", servletBase);
            initParams.put("export-all", "true");
            httpService.get().registerServlet("/git", new FabricGitServlet(git, curator.get()), initParams, secure);

            if (mbeanServer.getOptional() != null) {
                StandardMBean mbean = new StandardMBean(new io.fabric8.git.http.GitHttpEndpoint(this), GitHttpEndpointMBean.class);
                mbeanServer.get().registerMBean(mbean, objectName);
            }
        }
    }

    /**
     * The complexity of this code is due to problems with jgit and Windows file system.
     * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=300084
     */
    private void unregisterServlet() {
        try {
            if (mbeanServer.getOptional() != null) {
                mbeanServer.get().unregisterMBean(objectName);
            }
        } catch (Exception e) {
            LOGGER.warn("Exception during " + objectName + " unregistration: " + e.getMessage(), e);
        }
        synchronized (gitRemoteUrl) {
            if (basePath != null) {
                try {
                    httpService.get().unregister("/git");
                } catch (IllegalArgumentException e){
                    LOGGER.warn("/git Servlet wasn't registered. Unregistration not required.");
                }
                // this should foul jgit to use flush its cache and release locks for .pack files on Windows
                WindowCacheConfig cfg = new WindowCacheConfig();
                cfg.install();
                git.getRepository().close();
                git = null;

                boolean basePathExists = basePath.toFile().exists();
                for(int i = 0 ; i < 10; i++) {
                    try {
                        if (basePathExists) {
                            recursiveDelete(basePath.toFile());
                            basePathExists = false;
                        }
                        break;
                    } catch (IOException e) {
                        LOGGER.debug("Failed to recursively delete this filesystem path: {}", basePath, e);
                        System.gc();
                        try {
                            Thread.sleep(3 * 1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                if(basePathExists){
                    LOGGER.error("Failed to recursively delete this filesystem path: {}", basePath );
                } else{
                    LOGGER.info("Correctly removed old git repo: {}", basePath );
                }
            }
        }
    }

    private void recursiveDelete(File file) throws IOException{
        org.eclipse.jgit.util.FileUtils.delete(file,
                org.eclipse.jgit.util.FileUtils.RECURSIVE |org.eclipse.jgit.util.FileUtils.RETRY);
        LOGGER.info("Deleted path: {}", file.getAbsoluteFile());
    }

    private String readExternalGitUrl() {
        String result = null;
        try {
            Configuration conf = configAdmin.get().getConfiguration(Constants.DATASTORE_PID);
            if (conf == null) {
                LOGGER.warn("No configuration for pid " + Constants.DATASTORE_PID);
            } else {
                Dictionary<String, Object> properties = conf.getProperties();
                if (properties != null) {
                    Object o = conf.getProperties().get(Constants.GIT_REMOTE_URL);
                    result = o != null ? o.toString() : null;
                } else{
                    result = null;
                }
            }
        } catch (Throwable e) {
            LOGGER.error("Could not load config admin for pid " + Constants.DATASTORE_PID + ". Reason: " + e, e);
        }
        return result;
    }

    private GitNode createState() {
        RuntimeProperties sysprops = runtimeProperties.get();
        String runtimeIdentity = sysprops.getRuntimeIdentity();
        GitNode state = new GitNode("fabric-repo", runtimeIdentity);
        if (group != null && group.isMaster()) {
            String externalGitUrl = readExternalGitUrl();
            if (externalGitUrl != null) {
                state.setUrl(externalGitUrl);
            } else {
                String fabricRepoUrl = "${zk:" + runtimeIdentity + "/http}/git/fabric/";
                state.setUrl(fabricRepoUrl);
            }
        }
        return state;
    }

    private void zkCleanUp(Group<GitNode> group) {
        try {
            RuntimeProperties sysprops = runtimeProperties.get();
            String runtimeIdentity = sysprops.getRuntimeIdentity();

            curator.get().newNamespaceAwareEnsurePath(ZkPath.GIT.getPath()).ensure(curator.get().getZookeeperClient());
            List<String> allChildren = ZooKeeperUtils.getAllChildren(curator.get(), ZkPath.GIT.getPath());
            for (String path : allChildren) {
                String stringData = ZooKeeperUtils.getStringData(curator.get(), path);
                if (stringData.contains("\"container\":\"" + runtimeIdentity + "\"")) {
                    LOGGER.info("Found older ZK \"/fabric/registry/clusters/git\" entry for node " + runtimeIdentity);
                    ZooKeeperUtils.delete(curator.get(), path);
                    LOGGER.info("Older ZK \"/fabric/registry/clusters/git\" entry for node " + runtimeIdentity + " has been removed");
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to remove git server from registry.", e);
        }
    }

    void bindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.bind(service);
    }
    void unbindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.unbind(service);
    }

    void bindCurator(CuratorFramework curator) {
        this.curator.bind(curator);
    }
    void unbindCurator(CuratorFramework curator) {
        this.curator.unbind(curator);
    }

    void bindGitDataStore(GitDataStore service) {
        this.gitDataStore.bind(service);
    }
    void unbindGitDataStore(GitDataStore service) {
        this.gitDataStore.unbind(service);
    }

    void bindHttpService(HttpService service) {
        this.httpService.bind(service);
    }
    void unbindHttpService(HttpService service) {
        this.httpService.unbind(service);
    }

    void bindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.bind(service);
    }
    void unbindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.unbind(service);
    }

    void bindMBeanServer(MBeanServer mbeanServer) {
        this.mbeanServer.bind(mbeanServer);
    }
    void unbindMBeanServer(MBeanServer mbeanServer) {
        this.mbeanServer.unbind(mbeanServer);
    }

}

