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
package io.fabric8.patch.impl;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URLConnection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.fabric8.api.FabricService;
import io.fabric8.api.GitContext;
import io.fabric8.api.ProfileRegistry;
import io.fabric8.api.ProfileService;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.common.util.Base64Encoder;
import io.fabric8.git.GitDataStore;
import io.fabric8.git.internal.GitHelpers;
import io.fabric8.git.internal.GitOperation;
import io.fabric8.patch.FabricPatchService;
import io.fabric8.patch.management.BackupService;
import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchKind;
import io.fabric8.patch.management.PatchManagement;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.patch.management.ProfileUpdateStrategy;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.utils.version.VersionTable;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Version;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.fabric8.patch.management.Utils.stripSymbolicName;

@Component(immediate = true, metatype = false)
@Service(FabricPatchService.class)
public class FabricPatchServiceImpl implements FabricPatchService {

    public static Logger LOG = LoggerFactory.getLogger(FabricPatchServiceImpl.class);

    @Reference(referenceInterface = PatchManagement.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.STATIC)
    private PatchManagement patchManagement;

    @Reference(referenceInterface = CuratorFramework.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.DYNAMIC)
    private CuratorFramework curator;

    @Reference(referenceInterface = FabricService.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.DYNAMIC)
    private FabricService fabricService;

    @Reference(referenceInterface = GitDataStore.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.DYNAMIC)
    private GitDataStore gitDataStore;

    @Reference(referenceInterface = BackupService.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.STATIC)
    private BackupService backupService;

    @Reference(referenceInterface = RuntimeProperties.class, cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.DYNAMIC)
    private RuntimeProperties runtimeProperties;

    private OSGiPatchHelper helper;

    @Activate
    void activate(ComponentContext componentContext) throws IOException, BundleException {
        // Use system bundle' bundle context to avoid running into
        // "Invalid BundleContext" exceptions when updating bundles
        BundleContext bundleContext = componentContext.getBundleContext().getBundle(0).getBundleContext();
        File karafHome = new File(bundleContext.getProperty("karaf.home"));
        helper = new OSGiPatchHelper(karafHome, bundleContext);
    }

    @Override
    public PatchResult install(final Patch patch, boolean simulation, final String versionId,
                               boolean upload, final String username, final String password,
                               final ProfileUpdateStrategy strategy)
            throws IOException {

        // we start from the same state as in standalone mode - after successful patch:add
        // we have other things to do in fabric env however:
        // 1. check prerequisites
        // 2. we don't care about current state of framework - it'll be managed by fabric-agent and we don't
        //    necessary install a patch for this container we're in
        // 3. we don't do patchManagement.beginInstallation / patchManagement.commitInstallation here
        //    this will be done later - after updated fabric-agent is started
        // 4. we don't have to analyze bundles/features/repositories updates - these will be handled simply by
        //    updating profiles in specified version

        PatchKind kind = patch.getPatchData().isRollupPatch() ? PatchKind.ROLLUP : PatchKind.NON_ROLLUP;

        if (kind == PatchKind.NON_ROLLUP) {
            throw new UnsupportedOperationException("patch:fabric-install should be used for Rollup patches only");
        }

        String currentContainersVersionId = fabricService.getCurrentContainer().getVersionId();
        if (!simulation && versionId.equals(currentContainersVersionId)) {
            throw new UnsupportedOperationException("Can't install Rollup patch in current version. Please install" +
                    " this patch in new version and then upgrade existing container(s)");
        }

        fabricService.adapt(ProfileService.class).getRequiredVersion(versionId);

        // just a list of new bundle locations - in fabric the updatable version depends on the moment we
        // apply the new version to existing containers.
        List<BundleUpdate> bundleUpdatesInThisPatch = bundleUpdatesInPatch(patch);

        Presentation.displayBundleUpdates(bundleUpdatesInThisPatch, true);

        PatchResult result = new PatchResult(patch.getPatchData(), simulation, System.currentTimeMillis(),
                bundleUpdatesInThisPatch, null);

        if (!simulation) {
            // update profile definitions stored in Git. We don't update ${karaf.home}/fabric, becuase it is used
            // only once - when importing profiles during fabric:create.
            // when fabric is already available, we have to update (Git) repository information
            GitOperation operation = new GitOperation() {
                @Override
                public Object call(Git git, GitContext context) throws Exception {
                    // we can't pass git reference to patch-management
                    // because patch-management private-packages git library
                    // but we can leverage the write lock we have
                    GitHelpers.checkoutBranch(git, versionId);

                    // let's get back in history to the point before user changes (profile-edits), but not earlier
                    // than last R patch
                    String patchBranch = patchManagement.findLatestPatchRevision(git.getRepository().getDirectory(), versionId);

                    // now install profiles from patch just like there were no user changes
                    patchManagement.installProfiles(git.getRepository().getDirectory(), versionId, patch, strategy);

                    // and finally we have to merge user and patch changes to profiles.
                    patchManagement.mergeProfileChanges(patch, git.getRepository().getDirectory(), versionId, patchBranch);

                    context.commitMessage("Installing rollup patch \"" + patch.getPatchData().getId() + "\"");
                    return null;
                }
            };
            gitDataStore.gitOperation(new GitContext().requireCommit().setRequirePush(true), operation, null);

            // we don't have to configure anything more inside profiles!
            // containers that get upgraded to this new version will (should) have "patch-core" feature installed
            // and it (when started) will check if there's correct baseline + user changes available
            // patch-core will simply compare relevant property from io.fabric8.version PID with what is there
            // inside locally managed (trimmed down) patches/.management/history repository

            if (upload) {
                PatchManagement.UploadCallback callback = new PatchManagement.UploadCallback() {
                    @Override
                    public void doWithUrlConnection(URLConnection connection) throws ProtocolException {
                        if (connection instanceof HttpURLConnection) {
                            ((HttpURLConnection) connection).setRequestMethod("PUT");
                        }
                        if (username != null && password != null) {
                            connection.setRequestProperty("Authorization", "Basic " + Base64Encoder.encode(username + ":" + password));
                        }
                    }
                };
                patchManagement.uploadPatchArtifacts(patch.getPatchData(), fabricService.getMavenRepoUploadURI(), callback);
            }
        }

        return result;
    }

    /**
     * Simpler (than in standalone scenario) method of checking what bundles are updated with currently installed
     * {@link PatchKind#ROLLUP rollup patch}.
     * We only care about core bundles updated - all other bundles are handled by fabric agent.
     * @param patch
     * @return
     */
    private List<BundleUpdate> bundleUpdatesInPatch(Patch patch)
            throws IOException {

        List<BundleUpdate> updatesInThisPatch = new LinkedList<>();

        for (String newLocation : patch.getPatchData().getBundles()) {
            // [symbolicName, version] of the new bundle
            String[] symbolicNameVersion = helper.getBundleIdentity(newLocation);
            if (symbolicNameVersion == null || symbolicNameVersion[0] == null) {
                continue;
            }
            String sn = stripSymbolicName(symbolicNameVersion[0]);
            String vr = symbolicNameVersion[1];
            Version newVersion = VersionTable.getVersion(vr);
            BundleUpdate update = new BundleUpdate(sn, newVersion.toString(), newLocation, null, null);
            update.setIndependent(true);
            updatesInThisPatch.add(update);
        }

        return updatesInThisPatch;
    }

    @Override
    public String synchronize() throws Exception {
        final String[] remoteUrl = new String[] { null };

        patchManagement.pushPatchInfo();

        GitOperation operation = new GitOperation() {
            @Override
            public Object call(Git git, GitContext context) throws Exception {
                ProfileRegistry registry = fabricService.adapt(ProfileRegistry.class);
                Map<String, String> properties = registry.getDataStoreProperties();
                String username;
                String password;
                if (properties != null && properties.containsKey("gitRemoteUser")
                        && properties.containsKey("gitRemotePassword")) {
                    username = properties.get("gitRemoteUser");
                    password = properties.get("gitRemotePassword");
                } else {
                    username = ZooKeeperUtils.getContainerLogin(runtimeProperties);
                    password = ZooKeeperUtils.generateContainerToken(runtimeProperties, curator);
                }
                Iterable<PushResult> results = git.push()
                        .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
                        .setPushTags()
                        .setPushAll()
                        .call();

                logPushResult(results, git.getRepository());

                remoteUrl[0] = git.getRepository().getConfig().getString("remote", "origin", "url");
                return null;
            }
        };
        gitDataStore.gitOperation(new GitContext(), operation, null);
        return remoteUrl[0];
    }

    public void logPushResult(Iterable<PushResult> results, Repository repository) throws IOException {
        String local = repository.getDirectory().getCanonicalPath();

        for (PushResult result : results) {
            LOG.info(String.format("Pushed from %s to %s:", local, result.getURI()));
            Map<String, RemoteRefUpdate> map = new TreeMap<>();
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                map.put(update.getSrcRef(), update);
            }
            for (RemoteRefUpdate update : map.values()) {
                LOG.info(String.format(" - %s (%s)", update.getSrcRef(), update.getStatus()));
            }
        }
    }

}
