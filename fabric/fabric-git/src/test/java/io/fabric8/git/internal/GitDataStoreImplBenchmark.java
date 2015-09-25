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
import java.util.Random;

import static io.fabric8.common.util.Files.recursiveDelete;

public class GitDataStoreImplBenchmark extends GitDataStoreImplTestSupport {

    final int VERSION_COUNT = 100;

    @Test
    public void testUpdates() throws Exception {

        final GitDataStoreImpl gitDataStore = setup();

        System.out.println("==============================================================");
        System.out.println("Perf testing");
        System.out.println("==============================================================");


        String id = "55";
        long start = System.currentTimeMillis();
        Profile profile = ProfileBuilder.Factory.createFrom(gitDataStore.getProfile(id, "default"))
                .addConfiguration("foo", "foo3", "bar:" + System.currentTimeMillis())
                .getProfile();
        long d1 = System.currentTimeMillis() - start;

        while (true) {
            try {

                start = System.currentTimeMillis();
                gitDataStore.updateProfile(profile);
                long d2 = System.currentTimeMillis() - start;

                System.out.println("Get " + id + " Profile: " + d1 + " ms, Update: " + d2 + " ms");
            } catch (Throwable e) {
                System.out.println("Failed " + id + ": " + e);
            }
        }
    }

    @Test
    public void testUpdateReadMix() throws Exception {

        final GitDataStoreImpl gitDataStore = setup();

        System.out.println("==============================================================");
        System.out.println("Perf testing");
        System.out.println("==============================================================");

        for (int i = 0; i < 5; i++) {
            new Thread("Randomly Reading Profiles") {
                @Override
                public void run() {
                    Random random = new Random();
                    while (true) {
                        String id = "" + random.nextInt(VERSION_COUNT);
                        try {
                            long start = System.currentTimeMillis();
                            Profile profile = ProfileBuilder.Factory.createFrom(gitDataStore.getProfile(id, "default"))
                                    .addConfiguration("foo", "foo3", "bar:" + System.currentTimeMillis())
                                    .getProfile();
                            long d1 = System.currentTimeMillis() - start;
                            System.out.println("Read " + id + " Profile: " + d1 + " ms");
                        } catch (Throwable e) {
                            System.out.println("Failed " + id + ": " + e);
                        }
                    }
                }
            }.start();
        }

        Thread t = new Thread("Randomly Updating Profiles") {
            @Override
            public void run() {
                Random random = new Random();
                while (true) {
                    String id = "" + random.nextInt(VERSION_COUNT);
                    try {
                        long start = System.currentTimeMillis();
                        Profile profile = ProfileBuilder.Factory.createFrom(gitDataStore.getProfile(id, "default"))
                                .addConfiguration("foo", "foo3", "bar:" + System.currentTimeMillis())
                                .getProfile();
                        long d1 = System.currentTimeMillis() - start;

                        start = System.currentTimeMillis();
                        gitDataStore.updateProfile(profile);
                        long d2 = System.currentTimeMillis() - start;

                        System.out.println("Get " + id + " Profile: " + d1 + " ms, Update: " + d2 + " ms");
                    } catch (Throwable e) {
                        System.out.println("Failed " + id + ": " + e);
                    }
                }
            }
        };
        t.start();
        t.join();
    }


    private GitDataStoreImpl setup() throws Exception {
        System.out.println("==============================================================");
        System.out.println("Activating GitDataStoreImpl");
        System.out.println("==============================================================");
        final GitDataStoreImpl gitDataStore = createGitDataStore();

        System.out.println("==============================================================");
        System.out.println("Importing profiles");
        System.out.println("==============================================================");
        File profileDir = new File(projetDirectory(), "../fabric8-karaf/src/main/resources/distro/fabric/import");
        gitDataStore.importFromFileSystem(profileDir.getCanonicalPath());

        System.out.println("==============================================================");
        System.out.println("Creating versions.");
        System.out.println("==============================================================");
        String sourceVersion = "1.0";
        for (int i = 0; i < VERSION_COUNT; i++) {
            String id = "" + i;
            System.out.println("Creating version: "+id);
            gitDataStore.createVersion(sourceVersion, id, new HashMap<String, String>());
        }

        System.out.println("==============================================================");
        System.out.println("Loading Versions (init the version cache) : " + gitDataStore.getVersionIds());
        System.out.println("==============================================================");

        // Lets cache all the version..
        for (String s : gitDataStore.getVersionIds()) {
            gitDataStore.getVersion(s);
        }
        return gitDataStore;
    }


}
