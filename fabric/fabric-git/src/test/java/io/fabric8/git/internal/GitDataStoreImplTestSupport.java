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
package io.fabric8.git.internal;

import io.fabric8.api.*;
import io.fabric8.api.scr.AbstractRuntimeProperties;
import io.fabric8.service.ComponentConfigurer;
import io.fabric8.service.ZkDataStoreImpl;
import io.fabric8.zookeeper.bootstrap.BootstrapConfiguration;
import io.fabric8.zookeeper.bootstrap.DataStoreBootstrapTemplate;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static io.fabric8.common.util.Files.recursiveDelete;

public class GitDataStoreImplTestSupport {

    protected String zkURL;
    private CuratorFramework curator;
    private NIOServerCnxnFactory cnxnFactory;

    @Before
    public void init() throws Exception {
        int port = findFreePort();

        URL.setURLStreamHandlerFactory(new CustomURLStreamHandlerFactory());

        zkURL = "localhost:" + port;
        curator = CuratorFrameworkFactory.builder()
                .connectString(zkURL)
                .retryPolicy(new RetryOneTime(1000))
                .authorization("digest", ("fabric:admin").getBytes())
                .build();
        curator.start();
        curator.getACL();

        File dir = new File("target/zk/data");
        recursiveDelete(dir);
        cnxnFactory = startZooKeeper(port, "target/zk/data");
        curator.getZookeeperClient().blockUntilConnectedOrTimedOut();
    }

    private int findFreePort() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();
        return port;
    }

    private NIOServerCnxnFactory startZooKeeper(int port, String directory) throws Exception {
        ServerConfig cfg = new ServerConfig();
        cfg.parse(new String[]{Integer.toString(port), directory});

        ZooKeeperServer zkServer = new ZooKeeperServer();
        FileTxnSnapLog ftxn = new FileTxnSnapLog(new File(cfg.getDataLogDir()), new File(cfg.getDataDir()));
        zkServer.setTxnLogFactory(ftxn);
        zkServer.setTickTime(cfg.getTickTime());
        zkServer.setMinSessionTimeout(cfg.getMinSessionTimeout());
        zkServer.setMaxSessionTimeout(cfg.getMaxSessionTimeout());
        NIOServerCnxnFactory cnxnFactory = new NIOServerCnxnFactory();
        cnxnFactory.configure(cfg.getClientPortAddress(), cfg.getMaxClientCnxns());
        cnxnFactory.startup(zkServer);
        return cnxnFactory;
    }

    @After
    public void cleanup() throws Exception {
        curator.close();
        cnxnFactory.shutdown();
    }

    private RuntimeProperties createMockRuntimeProperties() {
        return new AbstractRuntimeProperties() {
            @Override
            public Path getDataPath() {
                return Paths.get("target/tmp");
            }

            @Override
            protected String getPropertyInternal(String key, String defaultValue) {
                return null;
            }
        };
    }

    protected GitDataStoreImpl createGitDataStore() throws Exception {
        RuntimeProperties runtimeProperties = createMockRuntimeProperties();
        CreateEnsembleOptions ensembleOptions =
                CreateEnsembleOptions.builder().
                        zookeeperPassword("admin").
                        build();

        recursiveDelete(runtimeProperties.getDataPath().toFile());

        BootstrapConfiguration.DataStoreOptions options = new BootstrapConfiguration.DataStoreOptions("root", new File("target/test-container"), zkURL, ensembleOptions);
        runtimeProperties.putRuntimeAttribute(DataStoreTemplate.class, new DataStoreBootstrapTemplate(options));

        FabricGitServiceImpl fabricGitService = new FabricGitServiceImpl();
        fabricGitService.bindRuntimeProperties(runtimeProperties);
        fabricGitService.activate();

        ComponentConfigurer componentConfigurer = new ComponentConfigurer();
        componentConfigurer.activate(null);

        ZkDataStoreImpl zkDataStore = new ZkDataStoreImpl() {
            @Override
            public String getDefaultVersion() {
                return "1.0" ;
            }
        };
        zkDataStore.bindCurator(curator);
        zkDataStore.bindRuntimeProperties(runtimeProperties);
        zkDataStore.activateComponent();

        final GitDataStoreImpl gitDataStore = new GitDataStoreImpl();
        gitDataStore.bindConfigurer(componentConfigurer);
        gitDataStore.bindGitService(fabricGitService);
        gitDataStore.bindRuntimeProperties(runtimeProperties);
        gitDataStore.bindGitProxyService(new GitProxyRegistrationHandler());
        gitDataStore.bindCurator(curator);
        gitDataStore.bindDataStore(zkDataStore);
        gitDataStore.activate(new HashMap<String, Object>());
        return gitDataStore;
    }


    protected File projetDirectory() {
        String testClasses = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        return new File(testClasses).getParentFile().getParentFile();
    }

    public static class CustomURLStreamHandlerFactory implements URLStreamHandlerFactory {
        @Override
        public URLStreamHandler createURLStreamHandler(String protocol) {
            if (!"mvn".equals(protocol)) {
                return null;
            }
            return new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL u) throws IOException {
                    return new URLConnection(u) {
                        @Override
                        public void connect() throws IOException {
                        }

                        @Override
                        public InputStream getInputStream() throws IOException {
                            ByteArrayOutputStream os = new ByteArrayOutputStream();
                            return new ByteArrayInputStream(os.toByteArray());
                        }
                    };
                }
            };
        }
    }

}
