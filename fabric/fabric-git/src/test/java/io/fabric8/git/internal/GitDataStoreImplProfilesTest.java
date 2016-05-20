/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.git.internal;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import io.fabric8.api.Constants;
import io.fabric8.api.DataStore;
import io.fabric8.api.GitContext;
import io.fabric8.api.Profile;
import io.fabric8.api.ProfileBuilder;
import io.fabric8.api.ProfileRegistry;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.Version;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.api.scr.ValidationSupport;
import io.fabric8.git.GitService;
import io.fabric8.git.PullPushPolicy;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;
import org.apache.commons.io.FileUtils;
import org.apache.curator.framework.CuratorFramework;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

/**
 * Test related to loading/saving/updating/refreshing profiles.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(ZooKeeperUtils.class)
public class GitDataStoreImplProfilesTest {

    // mocked
    private DataStore dataStore;
    private GitService gitService;
    private RuntimeProperties runtimeProperties;
    private CuratorFramework curator;
    private PullPushPolicy pullPushPolicy;

    // not mocked

    private ProfileRegistry profileRegistry;
    private GitDataStoreImpl gdsi;
    private Git git;

    @Before
    public void init() throws NoSuchFieldException, IllegalAccessException, IOException, GitAPIException {
        File repo = new File("target/fabric-git");
        FileUtils.deleteDirectory(repo);
        repo.mkdirs();
        git = Git.init().setDirectory(repo).call();
        git.commit().setMessage("init").call();
        git.tag().setName(GitHelpers.ROOT_TAG).call();

        dataStore = mock(DataStore.class);
        when(dataStore.getDefaultVersion()).thenReturn("1.0");
        gitService = mock(GitService.class);
        when(gitService.getGit()).thenReturn(git);
        runtimeProperties = mock(RuntimeProperties.class);
        when(runtimeProperties.getRuntimeIdentity()).thenReturn("root");
        curator = mock(CuratorFramework.class);
        pullPushPolicy = mock(PullPushPolicy.class);
        PullPushPolicy.PushPolicyResult ppResult = mock(PullPushPolicy.PushPolicyResult.class);
        when(ppResult.getPushResults()).thenReturn(new ArrayList<PushResult>());
        when(pullPushPolicy.doPush(any(GitContext.class), any(CredentialsProvider.class))).thenReturn(ppResult);

        mockStatic(ZooKeeperUtils.class);
        PowerMockito.when(ZooKeeperUtils.generateContainerToken(runtimeProperties, curator)).thenReturn("token");

        gdsi = new GitDataStoreImpl();
        this.<ValidationSupport>getField(gdsi, "active", ValidationSupport.class)
                .setValid();
        this.<ValidatingReference<DataStore>>getField(gdsi, "dataStore", ValidatingReference.class)
                .bind(dataStore);
        this.<ValidatingReference<GitService>>getField(gdsi, "gitService", ValidatingReference.class)
                .bind(gitService);
        this.<ValidatingReference<RuntimeProperties>>getField(gdsi, "runtimeProperties", ValidatingReference.class)
                .bind(runtimeProperties);
        this.<ValidatingReference<CuratorFramework>>getField(gdsi, "curator", ValidatingReference.class)
                .bind(curator);
        setField(gdsi, "dataStoreProperties", Map.class, new HashMap<String, Object>());
        setField(gdsi, "pullPushPolicy", PullPushPolicy.class, pullPushPolicy);

        profileRegistry = gdsi;
    }

    @Test
    public void readVersionAndRefreshProfile() {
        gdsi.importFromFileSystem("src/test/resources/distros/distro1/fabric/import");

        Version version = profileRegistry.getVersion("1.0");
        assertNotNull(version);
        Profile defaultProfile = version.getProfile("default");
        assertNotNull(defaultProfile);
        assertThat(defaultProfile.getBundles().size(), equalTo(1));
        assertThat(defaultProfile.getBundles().get(0), equalTo("mvn:io.fabric8/fabric-amazing/${version:fabric}"));
        assertThat(defaultProfile.getFeatures().size(), equalTo(1));
        assertThat(defaultProfile.getFeatures().get(0), equalTo("extraordinary"));
        assertThat(defaultProfile.getConfigurations().size(), equalTo(2));
        assertThat(defaultProfile.getConfigurations().get("my.special.pid").get("property2"), equalTo("value2"));
        assertThat(defaultProfile.getConfigurations().get("io.fabric8.agent").get("some.other.property"), equalTo("valueX"));
        assertThat(defaultProfile.getConfigurations().get("io.fabric8.agent").get("io.fabric8.number.of.sources"), equalTo("42,142,200"));

        ProfileBuilder builder = ProfileBuilder.Factory.createFrom(defaultProfile);
        Map<String, String> agentConfiguration = builder.getConfiguration(Constants.AGENT_PID);
        // refresh
        agentConfiguration.put("lastRefresh." + defaultProfile.getId(), String.valueOf(System.currentTimeMillis()));
        agentConfiguration.put("io.fabric8.number.of.sources", "100");
        builder.addConfiguration(Constants.AGENT_PID, agentConfiguration);
        profileRegistry.updateProfile(builder.getProfile());
    }

    private Field findField(Object object, String name, Class clazz) {
        Field f = null;
        Class<?> clz = object.getClass();
        while (f == null && clz != Object.class) {
            Field[] fields = clz.getDeclaredFields();
            for (Field f1 : fields) {
                if (f1.getType() == clazz && f1.getName().equals(name)) {
                    f = f1;
                    break;
                }
            }
            clz = clz.getSuperclass();
        }

        if (f == null) {
            throw new IllegalArgumentException("No field \"" + name + "\" in " + clazz);
        }

        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(Object object, String name, Class<?> clazz) throws IllegalAccessException, NoSuchFieldException {
        Field f = findField(object, name, clazz);
        return (T) f.get(object);
    }

    private <T> void setField(Object object, String name, Class<?> clazz, T value) throws IllegalAccessException, NoSuchFieldException {
        Field f = findField(object, name, clazz);
        f.set(object, value);
    }

}
