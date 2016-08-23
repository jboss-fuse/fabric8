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
package io.fabric8.patch.management.impl;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric8.patch.management.Artifact;
import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.EnvService;
import io.fabric8.patch.management.EnvType;
import io.fabric8.patch.management.ManagedPatch;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchData;
import io.fabric8.patch.management.PatchDetailsRequest;
import io.fabric8.patch.management.PatchException;
import io.fabric8.patch.management.PatchKind;
import io.fabric8.patch.management.PatchManagement;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.patch.management.Pending;
import io.fabric8.patch.management.ProfileUpdateStrategy;
import io.fabric8.patch.management.Utils;
import io.fabric8.patch.management.conflicts.ConflictResolver;
import io.fabric8.patch.management.conflicts.PropertiesFileResolver;
import io.fabric8.patch.management.conflicts.Resolver;
import io.fabric8.patch.management.conflicts.ResolverEx;
import io.fabric8.patch.management.io.EOLFixingFileOutputStream;
import io.fabric8.patch.management.io.EOLFixingFileUtils;
import io.fabric8.patch.management.io.ProfileFileUtils;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.RevertCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Constants;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.service.log.LogService;

import static io.fabric8.patch.management.Artifact.isSameButVersion;
import static io.fabric8.patch.management.Utils.*;

/**
 * <p>An implementation of Git-based patch management system. Deals with patch distributions and their unpacked
 * content.</p>
 * <p>This class delegates lower-level operations to {@link GitPatchRepository} and performs more complex git
 * operations in temporary clone+working copies.</p>
 */
public class GitPatchManagementServiceImpl implements PatchManagement, GitPatchManagementService {

    private static final String[] MANAGED_DIRECTORIES = new String[] {
            "bin", "etc", "lib", "fabric", "licenses", "metatype"
    };

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("patch-management-(\\d+\\.\\d+\\.\\d+(?:\\.[^\\.]+)?)\\.jar");

    private static final String MARKER_BASELINE_COMMIT_PATTERN = "[PATCH/baseline] Installing baseline-%s";
    private static final String MARKER_BASELINE_ROOT_COMMIT_PATTERN = "[PATCH/baseline] Installing baseline-%s";
    private static final String MARKER_BASELINE_CHILD_COMMIT_PATTERN = "[PATCH/baseline] Installing baseline-child-%s";
    private static final String MARKER_BASELINE_SSH_COMMIT_PATTERN = "[PATCH/baseline] Installing baseline-%s-%s";

    private static final String MARKER_BASELINE_RESET_OVERRIDES_PATTERN
            = "[PATCH/baseline] baseline-%s - resetting etc/overrides.properties";
    private static final String MARKER_BASELINE_REPLACE_PATCH_FEATURE_PATTERN
            = "[PATCH/baseline] baseline-%s - switching to patch feature repository %s";

    /* Patterns for rollup patch installation */
    private static final String MARKER_R_PATCH_INSTALLATION_PATTERN = "[PATCH] Installing rollup patch %s";
    private static final String MARKER_R_PATCH_RESET_OVERRIDES_PATTERN = "[PATCH] Rollup patch %s - resetting etc/overrides.properties";

    /* Patterns for non-rollup patch installation */
    private static final String MARKER_P_PATCH_INSTALLATION_PATTERN = "[PATCH] Installing patch %s";

    /** A pattern of commit message when installing patch-management (this) bundle in etc/startup.properties */
    private static final String MARKER_PATCH_MANAGEMENT_INSTALLATION_COMMIT_PATTERN
            = "[PATCH/management] patch-management-%s.jar installed in etc/startup.properties";

    /** Commit message when applying user changes to managed directories */
    private static final String MARKER_USER_CHANGES_COMMIT = "[PATCH] Apply user changes";

    private final BundleContext bundleContext;
    private final BundleContext systemContext;

    private EnvType env = EnvType.UNKNOWN;
    // if true, this container is the one that manages patches- branches for other containers in fabric mode
    // in non fabric mode this is irrelevant
    private boolean master = false;

    private GitPatchRepository gitPatchRepository;
    private ConflictResolver conflictResolver = new ConflictResolver();
    private EnvService envService;

    private AtomicBoolean aligning = new AtomicBoolean(false);

    // ${karaf.home}
    private File karafHome;
    // ${karaf.base}
    private File karafBase;
    // ${karaf.data}
    private File karafData;
    // main patches directory at ${fuse.patch.location} (defaults to ${karaf.home}/patches)
    private File patchesDir;

    // latched when git repository is initialized
    private CountDownLatch initialized = new CountDownLatch(1);

    /* patch installation support */

    protected Map<String, Git> pendingTransactions = new HashMap<>();
    protected Map<String, PatchKind> pendingTransactionsTypes = new HashMap<>();

    protected Map<String, BundleListener> pendingPatchesListeners = new HashMap<>();

    /**
     * <p>Creates patch management service</p>
     * <p>It checks the environment it's running at and use different strategies to initialize low level
     * structures - like the place where patch management history is kept (different for fabric and standalone
     * cases)</p>
     * @param context
     */
    public GitPatchManagementServiceImpl(BundleContext context) throws IOException {
        this.bundleContext = context;
        this.systemContext = context.getBundle(0).getBundleContext();
        karafHome = new File(systemContext.getProperty("karaf.home"));
        karafBase = new File(systemContext.getProperty("karaf.base"));
        karafData = new File(systemContext.getProperty("karaf.data"));

        envService = new DefaultEnvService(systemContext, karafHome, karafBase);
        env = envService.determineEnvironmentType();

        if (env == EnvType.UNKNOWN) {
            return;
        }

        String patchLocation = systemContext.getProperty("fuse.patch.location");
        if (patchLocation == null) {
            if (env == EnvType.STANDALONE_CHILD) {
                // admin:create child shares patch git repository with parent
                patchLocation = new File(karafHome, "patches").getCanonicalPath();
            } else {
                patchLocation = new File(karafBase, "patches").getCanonicalPath();
            }
        }
        patchesDir = new File(patchLocation);
        if (!patchesDir.isDirectory()) {
            patchesDir.mkdirs();
        }

        // always init/open local (${karaf.base}/patches/.management/history) repo
        // but in fabric mode, we will connect it to ${karaf.data}/git/local/fabric/.git
        File patchRepositoryLocation = new File(patchesDir, GitPatchRepositoryImpl.MAIN_GIT_REPO_LOCATION);

        GitPatchRepositoryImpl repository = new GitPatchRepositoryImpl(env, patchRepositoryLocation,
                karafHome, karafBase, karafData, patchesDir);
        setGitPatchRepository(repository);
    }

    public GitPatchRepository getGitPatchRepository() {
        return gitPatchRepository;
    }

    public void setGitPatchRepository(GitPatchRepository repository) {
        this.gitPatchRepository = repository;
    }

    @Override
    public List<Patch> listPatches(boolean details) throws PatchException {
        List<Patch> patches = new LinkedList<>();
        File[] patchDescriptors = patchesDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".patch") && new File(dir, name).isFile();
            }
        });

        try {
            for (File pd : patchDescriptors) {
                Patch p = loadPatch(pd, details);
                patches.add(p);
            }
        } catch (IOException e) {
            throw new PatchException(e.getMessage(), e);
        }

        return patches;
    }

    /**
     * Retrieves patch information from existing file
     * @param patchDescriptor existing file with patch descriptor (<code>*.patch</code> file)
     * @param details whether the returned {@link Patch} should contain {@link ManagedPatch} information
     * @return
     * @throws IOException
     */
    private Patch loadPatch(File patchDescriptor, boolean details) throws IOException {
        Patch p = new Patch();

        if (!patchDescriptor.exists() || !patchDescriptor.isFile()) {
            return null;
        }

        PatchData data = PatchData.load(new FileInputStream(patchDescriptor));
        p.setPatchData(data);

        File patchDirectory = new File(patchesDir, FilenameUtils.getBaseName(patchDescriptor.getName()));
        if (patchDirectory.exists() && patchDirectory.isDirectory()) {
            // not every descriptor downloaded may be a ZIP file, not every patch has content
            data.setPatchDirectory(patchDirectory);
        }
        data.setPatchLocation(patchesDir);

        File resultFile = new File(patchesDir, FilenameUtils.getBaseName(patchDescriptor.getName()) + ".patch.result");
        if (resultFile.exists() && resultFile.isFile()) {
            PatchResult result = PatchResult.load(data, new FileInputStream(resultFile));
            p.setResult(result);
        }

        if (details) {
            ManagedPatch mp = gitPatchRepository.getManagedPatch(data.getId());
            p.setManagedPatch(mp);
        }

        return p;
    }

    @Override
    public Patch loadPatch(PatchDetailsRequest request) throws PatchException {
        File descriptor = new File(patchesDir, request.getPatchId() + ".patch");
        try {
            Patch patch = loadPatch(descriptor, true);
            if (patch == null) {
                return null;
            }
            Git repo = gitPatchRepository.findOrCreateMainGitRepository();
            List<DiffEntry> diff = null;
            if (request.isFiles() || request.isDiff()) {
                // fetch the information from git
                ObjectId commitId = repo.getRepository().resolve(patch.getManagedPatch().getCommitId());
                RevCommit commit = new RevWalk(repo.getRepository()).parseCommit(commitId);
                diff = gitPatchRepository.diff(repo, commit.getParent(0), commit);
            }
            if (request.isBundles()) {
                // it's already in PatchData
            }
            if (request.isFiles() && diff != null) {
                for (DiffEntry de : diff) {
                    DiffEntry.ChangeType ct = de.getChangeType();
                    String newPath = de.getNewPath();
                    String oldPath = de.getOldPath();
                    switch (ct) {
                        case ADD:
                            patch.getManagedPatch().getFilesAdded().add(newPath);
                            break;
                        case MODIFY:
                            patch.getManagedPatch().getFilesModified().add(newPath);
                            break;
                        case DELETE:
                            patch.getManagedPatch().getFilesRemoved().add(oldPath);
                            break;
                    }
                }
            }
            if (request.isDiff() && diff != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DiffFormatter formatter = new DiffFormatter(baos);
                formatter.setContext(4);
                formatter.setRepository(repo.getRepository());
                for (DiffEntry de : diff) {
                    formatter.format(de);
                }
                formatter.flush();
                patch.getManagedPatch().setUnifiedDiff(new String(baos.toByteArray(), "UTF-8"));
            }
            return patch;
        } catch (IOException | GitAPIException e) {
            throw new PatchException(e.getMessage(), e);
        }
    }

    @Override
    public List<PatchData> fetchPatches(URL url) throws PatchException {
        try {
            List<PatchData> patches = new ArrayList<>(1);

            File patchFile = new File(patchesDir, Long.toString(System.currentTimeMillis()) + ".patch.tmp");
            InputStream input = url.openStream();
            FileOutputStream output = new FileOutputStream(patchFile);
            ZipFile zf = null;
            try {
                IOUtils.copy(input, output);
            } finally {
                IOUtils.closeQuietly(input);
                IOUtils.closeQuietly(output);
            }
            try {
                zf = new ZipFile(patchFile);
            } catch (IOException ignored) {
                if (!FilenameUtils.getExtension(url.getFile()).equals("patch")) {
                    throw new PatchException("Patch should be ZIP file or *.patch descriptor");
                }
            }

            // patchFile may "be" a patch descriptor or be a ZIP file containing descriptor
            PatchData patchData = null;
            // in case patch ZIP file has no descriptor, we'll "generate" patch data on the fly
            // no descriptor -> assume we have rollup patch or even full, new distribution
            PatchData fallbackPatchData = new PatchData(FilenameUtils.getBaseName(url.getPath()));
            fallbackPatchData.setGenerated(true);
            fallbackPatchData.setRollupPatch(true);
            fallbackPatchData.setPatchDirectory(new File(patchesDir, fallbackPatchData.getId()));
            fallbackPatchData.setPatchLocation(patchesDir);

            if (zf != null) {
                File systemRepo = getSystemRepository(karafHome, systemContext);
                try {
                    List<ZipArchiveEntry> otherResources = new LinkedList<>();
                    boolean skipRootDir = false;
                    for (Enumeration<ZipArchiveEntry> e = zf.getEntries(); e.hasMoreElements(); ) {
                        ZipArchiveEntry entry = e.nextElement();
                        if (!skipRootDir && entry.isDirectory()
                                && (entry.getName().startsWith("jboss-fuse-")
                                || entry.getName().startsWith("jboss-a-mq-"))) {
                            skipRootDir = true;
                        }
                        if (entry.isDirectory() || entry.isUnixSymlink()) {
                            continue;
                        }
                        String name = entry.getName();
                        if (skipRootDir) {
                            name = name.substring(name.indexOf('/') + 1);
                        }
                        if (!name.contains("/") && name.endsWith(".patch")) {
                            // patch descriptor in ZIP's root directory
                            if (patchData == null) {
                                // load data from patch descriptor inside ZIP. This may or may not be a rollup
                                // patch
                                File target = new File(patchesDir, name);
                                extractZipEntry(zf, entry, target);
                                patchData = loadPatchData(target);
                                patchData.setGenerated(false);
                                File targetDirForPatchResources = new File(patchesDir, patchData.getId());
                                patchData.setPatchDirectory(targetDirForPatchResources);
                                patchData.setPatchLocation(patchesDir);
                                target.renameTo(new File(patchesDir, patchData.getId() + ".patch"));
                                patches.add(patchData);
                            } else {
                                throw new PatchException(
                                        String.format("Multiple patch descriptors: already have patch %s and now encountered entry %s",
                                                patchData.getId(), name));
                            }
                        } else {
                            File target = null;
                            String relativeName = null;
                            if (name.startsWith("system/")) {
                                // copy to ${karaf.default.repository}
                                relativeName = name.substring("system/".length());
                                target = new File(systemRepo, relativeName);
                            } else if (name.startsWith("repository/")) {
                                // copy to ${karaf.default.repository}
                                relativeName = name.substring("repository/".length());
                                target = new File(systemRepo, relativeName);
                            } else {
                                // other files that should be applied to ${karaf.home} when the patch is installed
                                otherResources.add(entry);
                            }
                            if (target != null) {
                                // we unzip to system repository
                                extractAndTrackZipEntry(fallbackPatchData, zf, entry, target, skipRootDir);
                            }
                        }
                    }

                    File targetDirForPatchResources = new File(patchesDir, patchData == null ? fallbackPatchData.getId() : patchData.getId());
                    // now copy non-maven resources (we should now know where to copy them)
                    for (ZipArchiveEntry entry : otherResources) {
                        String name = entry.getName();
                        if (skipRootDir) {
                            name = name.substring(name.indexOf('/'));
                        }
                        File target = new File(targetDirForPatchResources, name);
                        extractAndTrackZipEntry(fallbackPatchData, zf, entry, target, skipRootDir);
                    }
                } finally {
                    if (zf != null) {
                        zf.close();
                    }
                    if (patchFile != null) {
                        patchFile.delete();
                    }
                }
            } else {
                // If the file is not a zip/jar, assume it's a single patch file
                patchData = loadPatchData(patchFile);
                // no patch directory - no attached content, assuming only references to bundles
                patchData.setPatchDirectory(null);
                patchFile.renameTo(new File(patchesDir, patchData.getId() + ".patch"));
                patches.add(patchData);
            }

            if (patches.size() == 0) {
                // let's use generated patch descriptor
                File generatedPatchDescriptor = new File(patchesDir, fallbackPatchData.getId() + ".patch");
                FileOutputStream out = new FileOutputStream(generatedPatchDescriptor);
                try {
                    fallbackPatchData.storeTo(out);
                } finally {
                    IOUtils.closeQuietly(out);
                }
                patches.add(fallbackPatchData);
            }

            return patches;
        } catch (IOException e) {
            throw new PatchException("Unable to download patch from url " + url, e);
        }
    }

    @Override
    public void uploadPatchArtifacts(PatchData patchData, URI uploadAddress, UploadCallback callback) throws PatchException {
        try {
            Activator.log2(LogService.LOG_INFO, "Uploading artifacts to " + uploadAddress);

            List<File> artifacts = new LinkedList<>();
            for (String bundle : patchData.getBundles()) {
                String newUrl = Utils.mvnurlToPath(bundle);
                if (newUrl != null) {
                    File repoLocation = new File(Utils.getSystemRepository(karafHome, bundleContext), newUrl);
                    if (repoLocation.isFile()) {
                        artifacts.add(repoLocation);
                    }
                }
            }
            for (String featureRepository : patchData.getFeatureFiles()) {
                String newUrl = Utils.mvnurlToPath(featureRepository);
                if (newUrl != null) {
                    File repoLocation = new File(Utils.getSystemRepository(karafHome, bundleContext), newUrl);
                    if (repoLocation.isFile()) {
                        artifacts.add(repoLocation);
                    }
                }
            }
            for (String artifact : patchData.getOtherArtifacts()) {
                String newUrl = Utils.mvnurlToPath(artifact);
                if (newUrl != null) {
                    File repoLocation = new File(Utils.getSystemRepository(karafHome, bundleContext), newUrl);
                    if (repoLocation.isFile()) {
                        artifacts.add(repoLocation);
                    }
                }
            }
            int delta = artifacts.size() / 10;
            int count = 0;
            for (File f : artifacts) {
                if (++count % delta == 0) {
                    Activator.log2(LogService.LOG_DEBUG, String.format("Uploaded %d/%d", count, artifacts.size()));
                }
                String relativeName = Utils.relative(Utils.getSystemRepository(karafHome, bundleContext), f.getCanonicalFile());
                relativeName = relativeName.replace('\\', '/');
                URL uploadUrl = uploadAddress.resolve(relativeName).toURL();
                URLConnection con = uploadUrl.openConnection();
                callback.doWithUrlConnection(con);
                con.setDoInput(true);
                con.setDoOutput(true);
                con.connect();
                OutputStream os = con.getOutputStream();
                InputStream is = new FileInputStream(f);
                try {
                    IOUtils.copy(is, os);
                    if (con instanceof HttpURLConnection) {
                        int code = ((HttpURLConnection) con).getResponseCode();
                        if (code < 200 || code >= 300) {
                            throw new IOException("Error uploading patched artifacts: " + ((HttpURLConnection) con).getResponseMessage());
                        }
                    }
                } finally {
                    IOUtils.closeQuietly(is);
                    IOUtils.closeQuietly(os);
                }
            }
            Activator.log2(LogService.LOG_DEBUG, String.format("Uploaded %d/%d", count, artifacts.size()));
        } catch (Exception e) {
            throw new PatchException(e.getMessage(), e);
        }
    }

    /**
     * Reads content of patch descriptor into non-(yet)-managed patch data structure
     * @param patchDescriptor
     * @return
     */
    private PatchData loadPatchData(File patchDescriptor) throws IOException {
        Properties properties = new Properties();
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(patchDescriptor);
            properties.load(inputStream);
            boolean ok = properties.containsKey("id") && properties.containsKey("bundle.count");
            if (!ok) {
                throw new PatchException("Patch descriptor is not valid");
            }
            return PatchData.load(properties);
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    /**
     * <p>This method turns static information about a patch into managed patch - i.e., patch added to git
     * repository.</p>
     *
     * <p>Such patch has its own branch ready to be merged (when patch is installed). Before installation we can verify
     * the patch,
     * examine the content, check the differences, conflicts and perform simulation (merge to temporary branch created
     * from main patch branch)</p>
     *
     * <p>The strategy is as follows:<ul>
     *     <li><em>main patch branch</em> in git repository tracks all changes (from baselines, patch-management
     *     system, patches and user changes)</li>
     *     <li>Initially there are 3 commits: baseline, patch-management bundle installation in etc/startup.properties,
     *     initial user changes</li>
     *     <li>We always <strong>tag the baseline commit</strong></li>
     *     <li>User changes may be applied each time Framework is restarted</li>
     *     <li>When we add a patch, we create <em>named branch</em> from the <strong>latest baseline</strong></li>
     *     <li>When we install a patch, we <strong>merge</strong> the patch branch with the <em>main patch branch</em>
     *     (that may contain additional user changes)</li>
     *     <li>When patch ZIP contains new baseline distribution, after merging patch branch, we tag the merge commit
     *     in <em>main patch branch</em> branch as new baseline</li>
     *     <li>Branches for new patches will then be created from new baseline commit</li>
     * </ul></p>
     * @param patchData
     * @return
     */
    @Override
    public Patch trackPatch(PatchData patchData) throws PatchException {
        try {
            awaitInitialization();
        } catch (InterruptedException e) {
            throw new PatchException("Patch management system is not ready yet");
        }
        Git fork = null;
        try {
            Git mainRepository = gitPatchRepository.findOrCreateMainGitRepository();
            // prepare single fork for all the below operations
            fork = gitPatchRepository.cloneRepository(mainRepository, true);

            // 1. find current baseline
            RevTag latestBaseline = gitPatchRepository.findCurrentBaseline(fork);
            if (latestBaseline == null) {
                throw new PatchException("Can't find baseline distribution tracked in patch management. Is patch management initialized?");
            }

            // the commit from the patch should be available from main patch branch
            RevCommit commit = new RevWalk(fork.getRepository()).parseCommit(latestBaseline.getObject());

            // create dedicated branch for this patch. We'll immediately add patch content there so we can examine the
            // changes from the latest baseline
            gitPatchRepository.checkout(fork)
                    .setCreateBranch(true)
                    .setName("patch-" + patchData.getId())
                    .setStartPoint(commit)
                    .call();

            // copy patch resources (but not maven artifacts from system/ or repository/) to working copy
            if (patchData.getPatchDirectory() != null) {
                boolean removeTargetDir = patchData.isRollupPatch();
                copyManagedDirectories(patchData.getPatchDirectory(), fork.getRepository().getWorkTree(), removeTargetDir, false, false);
            }

            // add the changes
            fork.add().addFilepattern(".").call();

            // remove the deletes (without touching specially-managed etc/overrides.properties)
            for (String missing : fork.status().call().getMissing()) {
                if (!"etc/overrides.properties".equals(missing)) {
                    fork.rm().addFilepattern(missing).call();
                }
            }

            // record information about other "patches" included in added patch (e.g., Fuse patch
            // may contain patches to admin:create based containers in standalone mode)
            StringWriter sw = new StringWriter();
            sw.append("# tags for patches included in \"").append(patchData.getId()).append("\"\n");
            for (String bundle : patchData.getBundles()) {
                // maybe we'll be discovering other kinds of patches, but now - only for admin:create based
                // containers that want to patch:install patches added in root containers
                if (bundle.contains("mvn:org.apache.karaf.admin/org.apache.karaf.admin.core/")) {
                    Artifact a = Utils.mvnurlToArtifact(bundle, true);
                    if (a != null) {
                        sw.append(String.format(EnvType.STANDALONE_CHILD.getBaselineTagFormat(), a.getVersion())).append("\n");
                    }
                    break;
                }
            }
            FileUtils.write(new File(fork.getRepository().getWorkTree(), "patch-info.txt"), sw.toString());
            fork.add().addFilepattern(".").call();

            // commit the changes (patch vs. baseline) to patch branch
            gitPatchRepository.prepareCommit(fork, String.format("[PATCH] Tracking patch %s", patchData.getId())).call();

            // push the patch branch
            gitPatchRepository.push(fork, "patch-" + patchData.getId());

            // track other kinds of baselines found in the patch
            if (env.isFabric()) {
                trackBaselinesForRootContainer(fork);
                trackBaselinesForChildContainers(fork);
                trackBaselinesForSSHContainers(fork);
            } else {
                // for admin:create child containers
                trackBaselinesForChildContainers(fork);
            }

            return new Patch(patchData, gitPatchRepository.getManagedPatch(patchData.getId()));
        } catch (IOException | GitAPIException e) {
            throw new PatchException(e.getMessage(), e);
        } finally {
            if (fork != null) {
                gitPatchRepository.closeRepository(fork, true);
            }
        }
    }

    /**
     * This service is published before it can initialize the system. It may be an issue in integration tests.
     */
    private void awaitInitialization() throws InterruptedException {
        initialized.await(30, TimeUnit.SECONDS);
    }

    @Override
    public String beginInstallation(PatchKind kind) {
        String tx = null;
        try {
            Git fork = gitPatchRepository.cloneRepository(gitPatchRepository.findOrCreateMainGitRepository(), true);
            Ref installationBranch = null;

            // let's pick up latest user changes
            applyUserChanges(fork);

            switch (kind) {
                case ROLLUP:
                    // create temporary branch from the current baseline - rollup patch installation is a rebase
                    // of existing user changes on top of new baseline
                    RevTag currentBaseline = gitPatchRepository.findCurrentBaseline(fork);
                    installationBranch = gitPatchRepository.checkout(fork)
                            .setName(String.format("patch-install-%s", GitPatchRepository.TS.format(new Date())))
                            .setCreateBranch(true)
                            .setStartPoint(currentBaseline.getTagName() + "^{commit}")
                            .call();
                    break;
                case NON_ROLLUP:
                    // create temporary branch from main-patch-branch/HEAD - non-rollup patch installation is cherry-pick
                    // of non-rollup patch commit over existing user changes - we can fast forward when finished
                    installationBranch = gitPatchRepository.checkout(fork)
                            .setName(String.format("patch-install-%s", GitPatchRepository.TS.format(new Date())))
                            .setCreateBranch(true)
                            .setStartPoint(gitPatchRepository.getMainBranchName())
                            .call();
                    break;
            }

            pendingTransactionsTypes.put(installationBranch.getName(), kind);
            pendingTransactions.put(installationBranch.getName(), fork);

            return installationBranch.getName();
        } catch (IOException | GitAPIException e) {
            if (tx != null) {
                pendingTransactions.remove(tx);
                pendingTransactionsTypes.remove(tx);
            }
            throw new PatchException(e.getMessage(), e);
        }
    }

    @Override
    public void install(String transaction, Patch patch, List<BundleUpdate> bundleUpdatesInThisPatch) {
        transactionIsValid(transaction, patch);

        Git fork = pendingTransactions.get(transaction);
        try {
            switch (pendingTransactionsTypes.get(transaction)) {
                case ROLLUP: {
                    Activator.log2(LogService.LOG_INFO, String.format("Installing rollup patch \"%s\"", patch.getPatchData().getId()));

                    // we can install only one rollup patch within single transaction
                    // and it is equal to cherry-picking all user changes on top of transaction branch
                    // after cherry-picking the commit from the rollup patch branch
                    // rollup patches do their own update to startup.properties
                    // we're operating on patch branch, HEAD of the patch branch points to the baseline
                    ObjectId since = fork.getRepository().resolve("HEAD^{commit}");
                    // we'll pick all user changes between baseline and main patch branch without P installations
                    ObjectId to = fork.getRepository().resolve(gitPatchRepository.getMainBranchName() + "^{commit}");
                    Iterable<RevCommit> mainChanges = fork.log().addRange(since, to).call();
                    List<RevCommit> userChanges = new LinkedList<>();
                    for (RevCommit rc : mainChanges) {
                        if (isUserChangeCommit(rc)) {
                            userChanges.add(rc);
                        }
                    }

                    String patchRef = patch.getManagedPatch().getCommitId();
                    if (env == EnvType.STANDALONE_CHILD) {
                        // we're in a slightly different situation:
                        //  - patch was patch:added in root container
                        //  - its main commit should be used when patching full Fuse/AMQ container
                        //  - it created "side" commits (with tags) for this case of patching admin:create based containers
                        //  - those tags are stored in special patch-info.txt file within patch' commit
                        String patchInfo = gitPatchRepository.getFileContent(fork, patchRef, "patch-info.txt");
                        if (patchInfo != null) {
                            BufferedReader reader = new BufferedReader(new StringReader(patchInfo));
                            String line = null;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("#")) {
                                    continue;
                                }
                                Pattern p = Pattern.compile(env.getBaselineTagFormat().replace("%s", "(.*)"));
                                if (p.matcher(line).matches()) {
                                    // this means we have another commit/tag that we should chery-pick as a patch
                                    // for this standalone child container
                                    patchRef = line.trim();
                                }
                            }
                        } else {
                            // hmm, we actually can't patch standalone child container then...
                            Activator.log2(LogService.LOG_WARNING, String.format("Can't install rollup patch \"%s\" in admin container - no information about admin container patch", patch.getPatchData().getId()));
                            return;
                        }
                    }

                    if (env == EnvType.STANDALONE) {
                        // pick the rollup patch
                        fork.cherryPick()
                                .include(fork.getRepository().resolve(patchRef))
                                .setNoCommit(true)
                                .call();

                        gitPatchRepository.prepareCommit(fork,
                                String.format(MARKER_R_PATCH_INSTALLATION_PATTERN, patch.getPatchData().getId())).call();
                    } else if (env == EnvType.STANDALONE_CHILD) {
                        // rebase on top of rollup patch
                        fork.reset()
                                .setMode(ResetCommand.ResetType.HARD)
                                .setRef("refs/tags/" + patchRef + "^{commit}")
                                .call();
                    }

                    // next commit - reset overrides.properties - this is 2nd step of installing rollup patch
                    // we are doing it even if the commit is going to be empty - this is the same step as after
                    // creating initial baseline
                    resetOverrides(fork.getRepository().getWorkTree());
                    fork.add().addFilepattern("etc/overrides.properties").call();
                    RevCommit c = gitPatchRepository.prepareCommit(fork,
                            String.format(MARKER_R_PATCH_RESET_OVERRIDES_PATTERN, patch.getPatchData().getId())).call();

                    if (env == EnvType.STANDALONE) {
                        // tag the new rollup patch as new baseline
                        String newFuseVersion = determineVersion(fork.getRepository().getWorkTree(), "fuse");
                        fork.tag()
                                .setName(String.format(EnvType.STANDALONE.getBaselineTagFormat(), newFuseVersion))
                                .setObjectId(c)
                                .call();
                    }

                    // reapply those user changes that are not conflicting
                    // for each conflicting cherry-pick we do a backup of user files, to be able to restore them
                    // when rollup patch is rolled back
                    ListIterator<RevCommit> it = userChanges.listIterator(userChanges.size());
                    int prefixSize = Integer.toString(userChanges.size()).length();
                    int count = 1;

                    while (it.hasPrevious()) {
                        RevCommit userChange = it.previous();
                        String prefix = String.format("%0" + prefixSize + "d-%s", count++, userChange.getName());
                        CherryPickResult result = fork.cherryPick()
                                .include(userChange)
                                .setNoCommit(true)
                                .call();

                        // ENTESB-5492: remove etc/overrides.properties if there is such file left from old patch
                        // mechanism
                        File overrides = new File(fork.getRepository().getWorkTree(), "etc/overrides.properties");
                        if (overrides.isFile()) {
                            overrides.delete();
                            fork.rm().addFilepattern("etc/overrides.properties").call();
                        }

                        // if there's conflict here, prefer patch version (which is "ours" (first) in this case)
                        handleCherryPickConflict(patch.getPatchData().getPatchDirectory(), fork, result, userChange,
                                false, PatchKind.ROLLUP, prefix, true);

                        // always commit even empty changes - to be able to restore user changes when rolling back
                        // rollup patch.
                        // commit has the original commit id appended to the message.
                        // when we rebase on OLDER baseline (rollback) we restore backed up files based on this
                        // commit id (from patches/patch-id.backup/number-commit directory)
                        String newMessage = userChange.getFullMessage() + "\n\n";
                        newMessage += prefix;
                        gitPatchRepository.prepareCommit(fork, newMessage).call();

                        // we may have unadded changes - when file mode is changed
                        fork.reset().setMode(ResetCommand.ResetType.HARD).call();
                    }

                    // finally - let's get rid of all the tags related to non-rollup patches installed between
                    // previous baseline and previous HEAD, because installing rollup patch makes all previous P
                    // patches obsolete
                    RevWalk walk = new RevWalk(fork.getRepository());
                    RevCommit c1 = walk.parseCommit(since);
                    RevCommit c2 = walk.parseCommit(to);
                    Map<String, RevTag> tags = gitPatchRepository.findTagsBetween(fork, c1, c2);
                    for (Map.Entry<String, RevTag> entry : tags.entrySet()) {
                        if (entry.getKey().startsWith("patch-")) {
                            fork.tagDelete().setTags(entry.getKey()).call();
                            fork.push()
                                    .setRefSpecs(new RefSpec()
                                            .setSource(null)
                                            .setDestination("refs/tags/" + entry.getKey()))
                                    .call();
                        }
                    }
                    break;
                }
                case NON_ROLLUP: {
                    Activator.log2(LogService.LOG_INFO, String.format("Installing non-rollup patch \"%s\"", patch.getPatchData().getId()));

                    // simply cherry-pick patch commit to transaction branch
                    // non-rollup patches require manual change to artifact references in all files

                    // pick the non-rollup patch
                    RevCommit commit = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve(patch.getManagedPatch().getCommitId()));
                    CherryPickResult result = fork.cherryPick()
                            .include(commit)
                            .setNoCommit(true)
                            .call();
                    handleCherryPickConflict(patch.getPatchData().getPatchDirectory(), fork, result, commit,
                            true, PatchKind.NON_ROLLUP, null, true);

                    // there are several files in ${karaf.home} that need to be changed together with patch
                    // commit, to make them reference updated bundles (paths, locations, ...)
                    updateFileReferences(fork, patch.getPatchData(), bundleUpdatesInThisPatch);
                    updateOverrides(fork.getRepository().getWorkTree(), patch.getPatchData());
                    fork.add().addFilepattern(".").call();

                    // always commit non-rollup patch
                    RevCommit c = gitPatchRepository.prepareCommit(fork,
                        String.format(MARKER_P_PATCH_INSTALLATION_PATTERN, patch.getPatchData().getId())).call();

                    // we may have unadded changes - when file mode is changed
                    fork.reset().setMode(ResetCommand.ResetType.HARD).call();

                    // tag the installed patch (to easily rollback and to prevent another installation)
                    String tagName = String.format("patch-%s", patch.getPatchData().getId().replace(' ', '-'));
                    if (env == EnvType.STANDALONE_CHILD) {
                        tagName += "-" + gitPatchRepository.getStandaloneChildkarafName();
                    }
                    fork.tag()
                            .setName(tagName)
                            .setObjectId(c)
                            .call();

                    break;
                }
            }
        } catch (IOException | GitAPIException e) {
            throw new PatchException(e.getMessage(), e);
        }
    }

    /**
     * <p>Updates existing <code>etc/overrides.properties</code> after installing single {@link PatchKind#NON_ROLLUP}
     * patch.</p>
     * @param workTree
     * @param patchData
     */
    private void updateOverrides(File workTree, PatchData patchData) throws IOException {
        File overrides = new File(workTree, "etc/overrides.properties");
        List<String> currentOverrides = overrides.isFile() ? FileUtils.readLines(overrides) : new LinkedList<String>();

        for (String bundle : patchData.getBundles()) {
            Artifact artifact = mvnurlToArtifact(bundle, true);
            if (artifact == null) {
                continue;
            }

            // Compute patch bundle version and range
            VersionRange range;
            Version oVer = Utils.getOsgiVersion(artifact.getVersion());
            String vr = patchData.getVersionRange(bundle);
            String override;
            if (vr != null && !vr.isEmpty()) {
                override = bundle + ";range=" + vr;
                range = new VersionRange(vr);
            } else {
                override = bundle;
                Version v1 = new Version(oVer.getMajor(), oVer.getMinor(), 0);
                Version v2 = new Version(oVer.getMajor(), oVer.getMinor() + 1, 0);
                range = new VersionRange(VersionRange.LEFT_CLOSED, v1, v2, VersionRange.RIGHT_OPEN);
            }

            // Process overrides.properties
            boolean matching = false;
            boolean added = false;
            for (int i = 0; i < currentOverrides.size(); i++) {
                String line = currentOverrides.get(i).trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    Artifact overrideArtifact = mvnurlToArtifact(line, true);
                    if (overrideArtifact != null) {
                        Version ver = Utils.getOsgiVersion(overrideArtifact.getVersion());
                        if (isSameButVersion(artifact, overrideArtifact) && range.includes(ver)) {
                            matching = true;
                            if (ver.compareTo(oVer) < 0) {
                                // Replace old override with the new one
                                currentOverrides.set(i, override);
                                added = true;
                            }
                        }
                    }
                }
            }
            // If there was not matching bundles, add it
            if (!matching) {
                currentOverrides.add(override);
            }
        }

        FileUtils.writeLines(overrides, currentOverrides, IOUtils.LINE_SEPARATOR_UNIX);
    }

    /**
     * <p>Creates/truncates <code>etc/overrides.properties</code></p>
     * <p>Each baseline ships new feature repositories and from this point (or the point where new rollup patch
     * is installed) we should start with 0-sized overrides.properties in order to have easier non-rollup
     * patch installation - P-patch should not ADD overrides.properties - they have to only MODIFY it because
     * it's easier to revert such modification (in case P-patch is rolled back - probably in different order
     * than it was installed)</p>
     * @param karafHome
     * @throws IOException
     */
    private void resetOverrides(File karafHome) throws IOException {
        File overrides = new File(karafHome, "etc/overrides.properties");
        if (overrides.isFile()) {
            overrides.delete();
        }
        overrides.createNewFile();
    }

    @Override
    public void commitInstallation(String transaction) {
        transactionIsValid(transaction, null);

        Git fork = pendingTransactions.get(transaction);

        try {
            switch (pendingTransactionsTypes.get(transaction)) {
                case ROLLUP: {
                    // hard reset of main patch branch to point to transaction branch + apply changes to ${karaf.home}
                    gitPatchRepository.checkout(fork)
                            .setName(gitPatchRepository.getMainBranchName())
                            .call();

                    // before we reset main patch branch to originate from new baseline, let's find previous baseline
                    RevTag baseline = gitPatchRepository.findCurrentBaseline(fork);
                    RevCommit c1 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve(baseline.getTagName() + "^{commit}"));

                    // hard reset of main patch branch - to point to other branch, originating from new baseline
                    fork.reset()
                            .setMode(ResetCommand.ResetType.HARD)
                            .setRef(transaction)
                            .call();
                    gitPatchRepository.push(fork);

                    RevCommit c2 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve("HEAD"));

                    // apply changes from single range of commits
//                    applyChanges(fork, c1, c2);
                    applyChanges(fork, false);
                    break;
                }
                case NON_ROLLUP: {
                    // fast forward merge of main patch branch with transaction branch
                    gitPatchRepository.checkout(fork)
                            .setName(gitPatchRepository.getMainBranchName())
                            .call();
                    // current version of ${karaf.home}
                    RevCommit c1 = new RevWalk(fork.getRepository()).parseCommit(fork.getRepository().resolve("HEAD"));

                    // fast forward over patch-installation branch - possibly over more than 1 commit
                    fork.merge()
                            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                            .include(fork.getRepository().resolve(transaction))
                            .call();

                    gitPatchRepository.push(fork);

                    // apply a change from commits of all installed patches
                    RevCommit c2 = new RevWalk(fork.getRepository()).parseCommit(fork.getRepository().resolve("HEAD"));
                    applyChanges(fork, c1, c2);
//                    applyChanges(fork);
                    break;
                }
            }
            gitPatchRepository.push(fork);
        } catch (GitAPIException | IOException e) {
            throw new PatchException(e.getMessage(), e);
        } finally {
            gitPatchRepository.closeRepository(fork, true);
        }

        pendingTransactions.remove(transaction);
        pendingTransactionsTypes.remove(transaction);
    }

    @Override
    public void rollbackInstallation(String transaction) {
        transactionIsValid(transaction, null);

        Git fork = pendingTransactions.get(transaction);

        try {
            switch (pendingTransactionsTypes.get(transaction)) {
                case ROLLUP:
                case NON_ROLLUP:
                    // simply do nothing - do not push changes to origin
                    break;
            }
        } finally {
            gitPatchRepository.closeRepository(fork, true);
        }

        pendingTransactions.remove(transaction);
        pendingTransactionsTypes.remove(transaction);
    }

    @Override
    public void rollback(PatchData patchData) {
        Git fork = null;
        try {
            fork = gitPatchRepository.cloneRepository(gitPatchRepository.findOrCreateMainGitRepository(), true);
            Ref installationBranch = null;

            PatchKind kind = patchData.isRollupPatch() ? PatchKind.ROLLUP : PatchKind.NON_ROLLUP;

            switch (kind) {
                case ROLLUP: {
                    Activator.log2(LogService.LOG_INFO, String.format("Rolling back rollup patch \"%s\"", patchData.getId()));

                    // rolling back a rollup patch should rebase all user commits on top of current baseline
                    // to previous baseline
                    RevTag currentBaseline = gitPatchRepository.findCurrentBaseline(fork);
                    RevCommit c1 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve(currentBaseline.getTagName() + "^{commit}"));
                    // remember the commit to discover P patch tags installed on top of rolledback baseline
                    RevCommit since = c1;
                    RevCommit c2 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve("HEAD"));
                    RevCommit to = c2;
                    Iterable<RevCommit> mainChangesSinceRollupPatch = fork.log().addRange(c1, c2).call();
                    List<RevCommit> userChanges = new LinkedList<>();
                    for (RevCommit rc : mainChangesSinceRollupPatch) {
                        if (isUserChangeCommit(rc)) {
                            userChanges.add(rc);
                        }
                    }

                    if (env == EnvType.STANDALONE) {
                        // remove the tag
                        fork.tagDelete()
                                .setTags(currentBaseline.getTagName())
                                .call();
                    }

                    // baselines are stacked on each other
                    RevTag previousBaseline = gitPatchRepository.findNthPreviousBaseline(fork, env == EnvType.STANDALONE ? 0 : 1);
                    c1 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve(previousBaseline.getTagName() + "^{commit}"));

                    // hard reset of main patch branch - to point to other branch, originating from previous baseline
                    fork.reset()
                            .setMode(ResetCommand.ResetType.HARD)
                            .setRef(previousBaseline.getTagName() + "^{commit}")
                            .call();

                    // reapply those user changes that are not conflicting
                    ListIterator<RevCommit> it = userChanges.listIterator(userChanges.size());

                    Status status = fork.status().call();
                    if (!status.isClean()) {
                        // unstage any garbage
                        fork.reset()
                                .setMode(ResetCommand.ResetType.MIXED)
                                .call();
                        for (String p : status.getModified()) {
                            gitPatchRepository.checkout(fork).addPath(p).call();
                        }
                    }
                    while (it.hasPrevious()) {
                        RevCommit userChange = it.previous();
                        CherryPickResult cpr = fork.cherryPick()
                                .include(userChange.getId())
                                .setNoCommit(true)
                                .call();

                        // this time prefer user change on top of previous baseline - this change shouldn't be
                        // conflicting, because when rolling back, patch change was preferred over user change
                        handleCherryPickConflict(patchData.getPatchDirectory(), fork, cpr, userChange,
                                true, PatchKind.ROLLUP, null, false);

                        // restore backed up content from the reapplied user change
                        String[] commitMessage = userChange.getFullMessage().split("\n\n");
                        if (commitMessage.length > 1) {
                            // we have original commit (that had conflicts) stored in this commit's full message
                            String ref = commitMessage[commitMessage.length - 1];
                            File backupDir = new File(patchesDir, patchData.getId() + ".backup");
                            backupDir = new File(backupDir, ref);
                            if (backupDir.exists() && backupDir.isDirectory()) {
                                Activator.log2(LogService.LOG_DEBUG, String.format("Restoring content of %s", backupDir.getCanonicalPath()));
                                copyManagedDirectories(backupDir, karafBase, false, false, false);
                            }
                        }

                        gitPatchRepository.prepareCommit(fork, userChange.getFullMessage()).call();
                    }

                    gitPatchRepository.push(fork);
                    if (env == EnvType.STANDALONE) {
                        // remove remote tag
                        fork.push()
                                .setRefSpecs(new RefSpec()
                                        .setSource(null)
                                        .setDestination("refs/tags/" + currentBaseline.getTagName()))
                                .call();
                    }

                    // remove tags related to non-rollup patches installed between
                    // rolled back baseline and previous HEAD, because rolling back to previous rollup patch
                    // (previous baseline) equal effectively to starting from fresh baseline
                    RevWalk walk = new RevWalk(fork.getRepository());
                    Map<String, RevTag> tags = gitPatchRepository.findTagsBetween(fork, since, to);
                    for (Map.Entry<String, RevTag> entry : tags.entrySet()) {
                        if (entry.getKey().startsWith("patch-")) {
                            fork.tagDelete().setTags(entry.getKey()).call();
                            fork.push()
                                    .setRefSpecs(new RefSpec()
                                            .setSource(null)
                                            .setDestination("refs/tags/" + entry.getKey()))
                                    .call();
                        }
                    }

                    // HEAD of main patch branch after reset and cherry-picks
                    c2 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve("HEAD"));
//                    applyChanges(fork, c1, c2);
                    applyChanges(fork, false);

                    break;
                }
                case NON_ROLLUP: {
                    Activator.log2(LogService.LOG_INFO, String.format("Rolling back non-rollup patch \"%s\"", patchData.getId()));

                    // rolling back a non-rollup patch is a revert of the patch commit and removal of patch tag

                    String patchTagName = String.format("patch-%s",
                            env == EnvType.STANDALONE ? patchData.getId() : patchData.getId() + "-" + gitPatchRepository.getStandaloneChildkarafName());
                    ObjectId oid = fork.getRepository().resolve(patchTagName);
                    if (oid == null) {
                        throw new PatchException(String.format("Can't find installed patch (tag %s is missing)",
                                patchTagName));
                    }
                    RevCommit commit = new RevWalk(fork.getRepository()).parseCommit(oid);

                    RevertCommand revertCommand = fork.revert().include(commit);
                    RevCommit reverted = revertCommand.call();
                    if (reverted == null) {
                        List<String> unmerged = revertCommand.getUnmergedPaths();
                        Activator.log2(LogService.LOG_WARNING, "Problem rolling back patch \"" + patchData.getId() + "\". The following files where updated later:");
                        for (String path : unmerged) {
                            Activator.log2(LogService.LOG_WARNING, " - " + path);
                        }
                        RevWalk walk = new RevWalk(fork.getRepository());
                        RevCommit head = walk.parseCommit(fork.getRepository().resolve("HEAD"));

                        Map<String, RevTag> tags = gitPatchRepository.findTagsBetween(fork, commit, head);
                        List<RevTag> laterPatches = new LinkedList<>();
                        if (tags.size() > 0) {
                            for (Map.Entry<String, RevTag> tag : tags.entrySet()) {
                                if (tag.getKey().startsWith("patch-")) {
                                    laterPatches.add(tag.getValue());
                                }
                            }
                            Activator.log2(LogService.LOG_INFO, "The following patches were installed after \"" + patchData.getId() + "\":");
                            for (RevTag t : laterPatches) {
                                String message = " - " + t.getTagName().substring("patch-".length());
                                RevObject object = walk.peel(t);
                                if (object != null) {
                                    RevCommit c = walk.parseCommit(object.getId());
                                    String date = GitPatchRepository.FULL_DATE.format(new Date(c.getCommitTime() * 1000L));
                                    message += " (" + date + ")";
                                }
                                Activator.log2(LogService.LOG_INFO, message);
                            }
                        }
                        return;
                    }

                    // TODO: should we restore the backup possibly created when instalilng P patch?

                    // remove the tag
                    fork.tagDelete()
                            .setTags(patchTagName)
                            .call();

                    gitPatchRepository.push(fork);
                    // remove remote tag
                    fork.push()
                            .setRefSpecs(new RefSpec()
                                    .setSource(null)
                                    .setDestination(String.format("refs/tags/%s", patchTagName)))
                            .call();

                    // HEAD of main patch branch after reset and cherry-picks
                    RevCommit c = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve("HEAD"));
                    applyChanges(fork, c.getParent(0), c);
//                    applyChanges(fork);

                    break;
                }
            }
        } catch (IOException | GitAPIException e) {
            throw new PatchException(e.getMessage(), e);
        } finally {
            if (fork != null) {
                gitPatchRepository.closeRepository(fork, true);
            }
        }
    }

    /**
     * Resolve merge conflict for fabric-mode patching of profile data.
     */
    protected void handleMergeConflict(Git fork, MergeResult result, String versionBranch, String patchBranch)
            throws GitAPIException, IOException {
        if (result.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
            Activator.log2(LogService.LOG_WARNING, "Problem with merging branch \"" + versionBranch +
                    "\" and \"" + patchBranch + "\"");

            // preferNew == true, because we prefer patch version.
            // here we handle only R patch and we're merging patch branch into current version branch.
            // that's why:
            // patch branch == "theirs" == "3" in `git ls-files -u` == DirCacheEntry.STAGE_3 == threeWayMerge[2]
            handleConflict(null, fork, true, null, false, "change from patch", null);
        }
    }

    /**
     * Resolve cherry-pick conflict before committing. Always prefer the change from patch, backup custom change
     * @param patchDirectory the source directory of the applied patch - used as a reference for backing up
     * conflicting files.
     * @param fork
     * @param result
     * @param commit conflicting commit
     * @param preferNew whether to use "theirs" change - the one from cherry-picked commit. for rollup patch, "theirs"
     * is user change (second, applied after patch), for non-rollup change, "theirs" is a change from patch (applied
     * after user change)
     * @param kind
     * @param cpPrefix prefix for a cherry-pick to have nice backup directory names.
     * @param performBackup if <code>true</code>, we backup rejected version (should be false during rollback of patches)
     */
    protected void handleCherryPickConflict(File patchDirectory, Git fork, CherryPickResult result, RevCommit commit,
                                            boolean preferNew, PatchKind kind, String cpPrefix, boolean performBackup)
            throws GitAPIException, IOException {
        if (result.getStatus() == CherryPickResult.CherryPickStatus.CONFLICTING) {
            Activator.log2(LogService.LOG_WARNING, "Problem with applying the change " + commit.getName() + ":");

            String choose = null, backup = null;
            switch (kind) {
                case ROLLUP:
                    choose = !preferNew ? "change from patch" : "custom change";
                    backup = !preferNew ? "custom change" : "change from patch";
                    break;
                case NON_ROLLUP:
                    choose = preferNew ? "change from patch" : "custom change";
                    backup = preferNew ? "custom change" : "change from patch";
                    break;
            }

            handleConflict(patchDirectory, fork, preferNew, cpPrefix, performBackup, choose, backup);
        }
    }

    private void handleConflict(File patchDirectory, Git fork, boolean preferNew, String cpPrefix, boolean performBackup, String choose, String backup) throws GitAPIException, IOException {
        Map<String, IndexDiff.StageState> conflicts = fork.status().call().getConflictingStageState();
        DirCache cache = fork.getRepository().readDirCache();
        // path -> [oursObjectId, baseObjectId, theirsObjectId]
        Map<String, ObjectId[]> threeWayMerge = new HashMap<>();

        // collect conflicts info
        for (int i = 0; i < cache.getEntryCount(); i++) {
            DirCacheEntry entry = cache.getEntry(i);
            if (entry.getStage() == DirCacheEntry.STAGE_0) {
                continue;
            }
            if (!threeWayMerge.containsKey(entry.getPathString())) {
                threeWayMerge.put(entry.getPathString(), new ObjectId[] { null, null, null });
            }
            if (entry.getStage() == DirCacheEntry.STAGE_1) {
                // base
                threeWayMerge.get(entry.getPathString())[1] = entry.getObjectId();
            }
            if (entry.getStage() == DirCacheEntry.STAGE_2) {
                // ours
                threeWayMerge.get(entry.getPathString())[0] = entry.getObjectId();
            }
            if (entry.getStage() == DirCacheEntry.STAGE_3) {
                // theirs
                threeWayMerge.get(entry.getPathString())[2] = entry.getObjectId();
            }
        }

        // resolve conflicts
        ObjectReader objectReader = fork.getRepository().newObjectReader();

        for (Map.Entry<String, ObjectId[]> entry : threeWayMerge.entrySet()) {
            if (entry.getKey().equals("patch-info.txt")) {
                fork.rm().addFilepattern(entry.getKey()).call();
                continue;
            }
            Resolver resolver = conflictResolver.getResolver(entry.getKey());
            // resolved version - either by custom resolved or using automatic algorithm
            String resolved = null;
            if (resolver != null && entry.getValue()[0] != null && entry.getValue()[2] != null) {
                // custom conflict resolution (don't expect DELETED_BY_X kind of conflict, only BOTH_MODIFIED)
                String message = String.format(" - %s (%s): %s", entry.getKey(), conflicts.get(entry.getKey()), "Using " + resolver.getClass().getName() + " to resolve the conflict");
                Activator.log2(LogService.LOG_INFO, message);

                // when doing custom resolution of conflict, we know that both user and patch has changed the file
                // in non-mergeable way.
                // If there was no resolver, we simply check what to choose by "preferNew" flag
                // But because we have custom resolver, we use "preferNew" flag to check which STAGE points to patch'
                // version and we select this patch' version of conflicting file as less important file inside
                // custom resolver
                File base = null, first = null, second = null;
                try {
                    ObjectLoader loader = null;
                    if (entry.getValue()[1] != null) {
                        base = new File(fork.getRepository().getWorkTree(), entry.getKey() + ".1");
                        loader = objectReader.open(entry.getValue()[1]);
                        try (FileOutputStream fos = new FileOutputStream(base)) {
                            loader.copyTo(fos);
                        }
                    }

                    // if preferNew == true (P patch) then "first" file (less important) will be file
                    // provided by patch ("theirs", STAGE_3)
                    first = new File(fork.getRepository().getWorkTree(), entry.getKey() + ".2");
                    loader = objectReader.open(entry.getValue()[preferNew ? 2 : 0]);
                    try (FileOutputStream fos = new FileOutputStream(first)) {
                        loader.copyTo(fos);
                    }

                    // "second", more important file will be user change
                    second = new File(fork.getRepository().getWorkTree(), entry.getKey() + ".3");
                    loader = objectReader.open(entry.getValue()[preferNew ? 0 : 2]);
                    try (FileOutputStream fos = new FileOutputStream(second)) {
                        loader.copyTo(fos);
                    }

                    // resolvers treat patch change as less important - user lines overwrite patch lines
                    if (resolver instanceof PropertiesFileResolver) {
                        // TODO: use options from patch:install / patch:fabric-install command
                        // by default we use a file that comes from patch and we may add property changes
                        // from user
                        // in R patch, preferNew == false, because patch comes first
                        // in P patch, preferNew == true, because patch comes last
                        // in R patch + fabric mode, preferNew == true, because we *merge* patch branch into version
                        //    branch
                        boolean useFirstChangeAsBase = true;
                        if (entry.getKey().startsWith("etc/")) {
                            // files in etc/ directory are "for user", so we use them as base (possibly changed
                            // by user - comments, layout, ...)
                            // files in e.g., fabric/import/fabric/profiles are "for Fuse", so we use patch version
                            // as base
                            useFirstChangeAsBase = false;
                        }
                        resolved = ((ResolverEx)resolver).resolve(first, base, second, useFirstChangeAsBase);
                    } else {
                        resolved = resolver.resolve(first, base, second);
                    }

                    if (resolved != null) {
                        FileUtils.write(new File(fork.getRepository().getWorkTree(), entry.getKey()), resolved);
                        fork.add().addFilepattern(entry.getKey()).call();
                    }
                } finally {
                    if (base != null) {
                        base.delete();
                    }
                    if (first != null) {
                        first.delete();
                    }
                    if (second != null) {
                        second.delete();
                    }
                }
            }
            if (resolved == null) {
                // automatic conflict resolution
                String message = String.format(" - %s (%s): Choosing %s", entry.getKey(), conflicts.get(entry.getKey()), choose);

                ObjectLoader loader = null;
                ObjectLoader loaderForBackup = null;
                // longer code, but more readable then series of elvis operators (?:)
                if (preferNew) {
                    switch (conflicts.get(entry.getKey())) {
                        case BOTH_ADDED:
                        case BOTH_MODIFIED:
                            loader = objectReader.open(entry.getValue()[2]);
                            loaderForBackup = objectReader.open(entry.getValue()[0]);
                            break;
                        case BOTH_DELETED:
                            break;
                        case DELETED_BY_THEM:
                            // ENTESB-6003: special case: when R patch removes something and we've modified it
                            // let's preserve our version
                            message = String.format(" - %s (%s): Keeping custom change", entry.getKey(), conflicts.get(entry.getKey()));
                            loader = objectReader.open(entry.getValue()[0]);
                            break;
                        case DELETED_BY_US:
                            loader = objectReader.open(entry.getValue()[2]);
                            break;
                    }
                } else {
                    switch (conflicts.get(entry.getKey())) {
                        case BOTH_ADDED:
                        case BOTH_MODIFIED:
                            loader = objectReader.open(entry.getValue()[0]);
                            loaderForBackup = objectReader.open(entry.getValue()[2]);
                            break;
                        case DELETED_BY_THEM:
                            loader = objectReader.open(entry.getValue()[0]);
                            break;
                        case BOTH_DELETED:
                        case DELETED_BY_US:
                            break;
                    }
                }

                Activator.log2(LogService.LOG_WARNING, message);

                if (loader != null) {
                    try (FileOutputStream fos = new FileOutputStream(new File(fork.getRepository().getWorkTree(), entry.getKey()))) {
                        loader.copyTo(fos);
                    }
                    fork.add().addFilepattern(entry.getKey()).call();
                } else {
                    fork.rm().addFilepattern(entry.getKey()).call();
                }

                if (performBackup) {
                    // the other entry should be backed up
                    if (loaderForBackup != null) {
                        File target = new File(patchDirectory.getParent(), patchDirectory.getName() + ".backup");
                        if (cpPrefix != null) {
                            target = new File(target, cpPrefix);
                        }
                        File file = new File(target, entry.getKey());
                        message = String.format("Backing up %s to \"%s\"", backup, file.getCanonicalPath());
                        Activator.log2(LogService.LOG_DEBUG, message);
                        file.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(file)) {
                            loaderForBackup.copyTo(fos);
                        }
                    }
                }
            }
        }
    }

    /**
     * Very important method - {@link PatchKind#NON_ROLLUP non rollup patches} do not ship such files as
     * <code>etc/startup.properties</code>, but we <strong>have to</strong> update references to artifacts from those
     * files to point them to updated bundles.
     * Also we have to update/add <code>etc/overrides.properties</code> to have features working.
     * @param fork
     * @param patchData
     * @param bundleUpdatesInThisPatch
     */
    private void updateFileReferences(Git fork, PatchData patchData, List<BundleUpdate> bundleUpdatesInThisPatch) {
        if (patchData.isRollupPatch()) {
            return;
        }

        /*
         * we generally have a white list of files to update. We'll update them line by line if needed
         * these are the files & patterns to change:
         * bin/admin, bin/admin.bat, bin/client, bin/client.bat:
         *  - system/MAVEN_LOCATION (or \ in *.bat files)
         * etc/config.properties:
         *  - ${karaf.default.repository}/MAVEN_LOCATION
         *  - org.apache.karaf.jaas.boot;version="2.4.0.redhat-620133", \
         *  - org.apache.karaf.jaas.boot.principal;version="2.4.0.redhat-620133", \
         *  - org.apache.karaf.management.boot;version="2.4.0.redhat-620133", \
         *  - org.apache.karaf.version;version="2.4.0.redhat-620133", \
         *  - org.apache.karaf.diagnostic.core;version="2.4.0.redhat-620133", \
         *  these are the versions exported from lib/karaf.jar and this may be changed by non-rollup patch too...
         * etc/startup.properties:
         *  - MAVEN_LOCATION
         * etc/org.apache.karaf.features.cfg:
         *  - don't touch that file. NON-ROLLUP patches handle features using etc/overrides.properties and ROLLUP
         *  patches overwrite this file
         */

        Map<String, String> locationUpdates = Utils.collectLocationUpdates(bundleUpdatesInThisPatch);

        // update some files in generic way
        updateReferences(fork, "bin/admin", "system/", locationUpdates);
        updateReferences(fork, "bin/client", "system/", locationUpdates);
        updateReferences(fork, "etc/startup.properties", "", locationUpdates);
        updateReferences(fork, "bin/admin.bat", "system/", locationUpdates, true);
        updateReferences(fork, "bin/client.bat", "system/", locationUpdates, true);
        updateReferences(fork, "etc/config.properties", "${karaf.default.repository}/", locationUpdates);

        // update system karaf package versions in etc/config.properties
        File configProperties = new File(fork.getRepository().getWorkTree(), "etc/config.properties");
        for (String file : patchData.getFiles()) {
            if ("lib/karaf.jar".equals(file)) {
                // update:
                //  - org.apache.karaf.version;version="2.4.0.redhat-620133", \
                String newVersion = getBundleVersion(new File(fork.getRepository().getWorkTree(), file));
                updateKarafPackageVersion(configProperties, newVersion, "org.apache.karaf.version");
            } else if ("lib/karaf-jaas-boot.jar".equals(file)) {
                // update:
                //  - org.apache.karaf.jaas.boot;version="2.4.0.redhat-620133", \
                //  - org.apache.karaf.jaas.boot.principal;version="2.4.0.redhat-620133", \
                String newVersion = getBundleVersion(new File(fork.getRepository().getWorkTree(), file));
                updateKarafPackageVersion(configProperties, newVersion,
                        "org.apache.karaf.jaas.boot",
                        "org.apache.karaf.jaas.boot.principal");
            } else if ("lib/karaf-jmx-boot.jar".equals(file)) {
                // update:
                //  - org.apache.karaf.management.boot;version="2.4.0.redhat-620133", \
                String newVersion = getBundleVersion(new File(fork.getRepository().getWorkTree(), file));
                updateKarafPackageVersion(configProperties, newVersion, "org.apache.karaf.management.boot");
            } else if (file.startsWith("lib/org.apache.karaf.diagnostic.core-") && file.endsWith(".jar")) {
                // update:
                //  - org.apache.karaf.diagnostic.core;version="2.4.0.redhat-620133", \
                String newVersion = getBundleVersion(new File(fork.getRepository().getWorkTree(), file));
                updateKarafPackageVersion(configProperties, newVersion, "org.apache.karaf.diagnostic.core");
            }
        }

        // update etc/overrides.properties
    }

    /**
     * Changes prefixed references (to artifacts in <code>${karaf.default.repository}</code>) according to
     * a list of bundle updates.
     * @param fork
     * @param file
     * @param prefix
     * @param locationUpdates
     * @param useBackSlash
     */
    protected void updateReferences(Git fork, String file, String prefix, Map<String, String> locationUpdates, boolean useBackSlash) {
        File updatedFile = new File(fork.getRepository().getWorkTree(), file);
        if (!updatedFile.isFile()) {
            return;
        }

        BufferedReader reader = null;
        StringWriter sw = new StringWriter();
        try {
            Activator.log2(LogService.LOG_INFO, "Updating \"" + file + "\"");

            reader = new BufferedReader(new FileReader(updatedFile));
            String line = null;
            while ((line = reader.readLine()) != null) {
                for (Map.Entry<String, String> entry : locationUpdates.entrySet()) {
                    String pattern = prefix + entry.getKey();
                    String replacement = prefix + entry.getValue();
                    if (useBackSlash) {
                        pattern = pattern.replaceAll("/", "\\\\");
                        replacement = replacement.replaceAll("/", "\\\\");
                    }
                    if (line.contains(pattern)) {
                        line = line.replace(pattern, replacement);
                    }
                }
                sw.append(line);
                if (useBackSlash) {
                    // Assume it's .bat file
                    sw.append("\r");
                }
                sw.append("\n");
            }
            IOUtils.closeQuietly(reader);
            FileUtils.write(updatedFile, sw.toString());
        } catch (Exception e) {
            Activator.log(LogService.LOG_ERROR, null, e.getMessage(), e, true);
        } finally {
            IOUtils.closeQuietly(reader);
        }
    }

    /**
     * Changes prefixed references (to artifacts in <code>${karaf.default.repository}</code>) according to
     * a list of bundle updates.
     * @param fork
     * @param file
     * @param prefix
     * @param locationUpdates
     */
    private void updateReferences(Git fork, String file, String prefix, Map<String, String> locationUpdates) {
        updateReferences(fork, file, prefix, locationUpdates, false);
    }

    /**
     * Retrieves <code>Bundle-Version</code> header from JAR file
     * @param file
     * @return
     * @throws FileNotFoundException
     */
    private String getBundleVersion(File file) {
        JarInputStream jis = null;
        try {
            jis = new JarInputStream(new FileInputStream(file));
            Manifest mf = jis.getManifest();
            return mf.getMainAttributes().getValue(Constants.BUNDLE_VERSION);
        } catch (Exception e) {
            return null;
        } finally {
            IOUtils.closeQuietly(jis);
        }
    }

    /**
     * Checks if the commit is user (non P-patch installation) change
     * @param rc
     * @return
     */
    protected boolean isUserChangeCommit(RevCommit rc) {
        return MARKER_USER_CHANGES_COMMIT.equals(rc.getShortMessage());
    }

    /**
     * Validates state of the transaction for install/commit/rollback purposes
     * @param transaction
     * @param patch
     */
    private void transactionIsValid(String transaction, Patch patch) {
        if (!pendingTransactions.containsKey(transaction)) {
            if (patch != null) {
                throw new PatchException(String.format("Can't proceed with \"%s\" patch - illegal transaction \"%s\".",
                        patch.getPatchData().getId(),
                        transaction));
            } else {
                throw new PatchException(String.format("Can't proceed - illegal transaction \"%s\".",
                        transaction));
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return patchesDir != null && patchesDir.isDirectory() && patchesDir.exists();
    }

    @Override
    public void start() throws IOException, GitAPIException {
        if (patchesDir != null) {
            gitPatchRepository.open();
        }
    }

    @Override
    public void stop() {
        if (patchesDir != null) {
            gitPatchRepository.close();
        }
    }

    /**
     * Check if Fuse/Fabric8 installation is correctly managed by patch mechanism. Check if main git repository
     * is created and is intialized with correct content, there are no conflicts and no pending updates in main Karaf
     * directory. After this method is invoked, we're basically ready to perform rollup patches backed up by git
     * repository.
     */
    @Override
    public void ensurePatchManagementInitialized() {
        Activator.log(LogService.LOG_INFO, "Configuring patch management system");

        Git fork = null;
        try {
            Git mainRepository = gitPatchRepository.findOrCreateMainGitRepository();

            if (env.isFabric()) {
                // let's fetch from origin to check if someone else already does fabric patch management
                mainRepository.fetch()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                        .setTagOpt(TagOpt.FETCH_TAGS)
                        .call();

                if (env == EnvType.FABRIC_FUSE || env == EnvType.FABRIC_AMQ) {
                    // I think it's enough to compare the main HEADs. if there are no remote branches
                    // for patch management or their HEADs are the same, we are the MAIN container that performs
                    // git patch management
                    ObjectId ours = mainRepository.getRepository()
                            .resolve("refs/heads/" + gitPatchRepository.getFuseRootContainerPatchBranchName());
                    ObjectId theirs = mainRepository.getRepository()
                            .resolve("refs/remotes/origin/" + gitPatchRepository.getFuseRootContainerPatchBranchName());
                    if (theirs == null || theirs.equals(ours)) {
                        // if ours is null, then we've just started git patch management
                        master = true;
                        gitPatchRepository.setMaster(true);
                    }
                }
            }

            // prepare single fork for all the below operations - switch to different branches later, as needed
            fork = gitPatchRepository.cloneRepository(mainRepository, true);

            if (env.isFabric() && master) {
                // track baselines for future - when containers are upgraded and their static resources
                // have to be rebased on new baselines
                // new baselines will be added when patches are installed
                trackBaselinesForRootContainer(fork);
                trackBaselinesForChildContainers(fork);
                trackBaselinesForSSHContainers(fork);
            }

            if (env == EnvType.STANDALONE) {
                // do standalone history initialization. We're in root Fuse/AMQ container
                String currentFuseVersion = determineVersion("fuse");

                // one of the steps may return a commit that has to be tagged as first baseline
                RevCommit baselineCommit = null;
                if (!gitPatchRepository.containsTag(fork, String.format(env.getBaselineTagFormat(), currentFuseVersion))) {
                    baselineCommit = trackBaselineRepository(fork);
                    RevCommit c2 = installPatchManagementBundle(fork);
                    if (c2 != null) {
                        baselineCommit = c2;
                    }
                }
                // because patch management is already installed, we have to add consecutive (post patch-management installation) changes
                applyUserChanges(fork);

                if (baselineCommit != null) {
                    // and we'll tag the baseline *after* steps related to first baseline
                    fork.tag()
                            .setName(String.format(env.getBaselineTagFormat(), currentFuseVersion))
                            .setObjectId(baselineCommit)
                            .call();

                    gitPatchRepository.push(fork);
                }

                // now we have to do the same for existing/future admin:create based child containers
                // it's the root container that takes care of this
                trackBaselinesForChildContainers(fork);

                // repository of patch data - already installed patches
                migrateOldPatchData();
            } else if (env == EnvType.STANDALONE_CHILD) {
                // we're in admin:create based child container. we share patch management git repository
                // with the container that created us
                String currentKarafVersion = determineVersion(karafBase, "karaf");
                String tagName = String.format(env.getBaselineTagFormat(), currentKarafVersion);

                RevTag tag = gitPatchRepository.findCurrentBaseline(fork);
                if (tag == null) {
                    // this means that containe's history was *never* on correct baseline - we're actually
                    // initializing container's history
                    // technically - we can't create first commit with user changes, because we'll have problems
                    // cherry picking them on correct first baseline - we'll have BOTH_ADDED conflict
                    // that's why we have to put first baseline-related commit to container's history
                    ensureCorrectContainerHistory(fork, currentKarafVersion);
                    // now a commit with current user changes
                    applyUserChanges(fork);
                    applyChanges(fork, true);
                } else if (!tagName.equals(tag.getTagName())) {
                    // history branch for this standalone child container is not correctly (re)based
                    // on relevant baseline, but it's not the first time we do it
                    applyUserChanges(fork);
                    ensureCorrectContainerHistory(fork, currentKarafVersion);
                    applyChanges(fork, true);
                }
            } else {
                // do fabric history branch initialization
                // each container has to make sure their private history branch is created and that it contains
                // tag related to the "version" of container (child: karaf, ssh: fabric8 or fuse, root: fuse or amq)
                String tagName = String.format(env.getBaselineTagFormat(), determineVersion(karafBase, env.getProductId()));

                RevTag tag = gitPatchRepository.findCurrentBaseline(fork);
                if (tag == null || !tagName.equals(tag.getTagName())) {
                    ensureCorrectContainerHistory(fork, null);
                    applyUserChanges(fork);
                }
            }

            // remove pending patches listeners
            for (BundleListener bl : pendingPatchesListeners.values()) {
                systemContext.removeBundleListener(bl);
            }
        } catch (GitAPIException | IOException e) {
            Activator.log(LogService.LOG_ERROR, null, e.getMessage(), e, true);
        } finally {
            initialized.countDown();
            if (fork != null) {
                gitPatchRepository.closeRepository(fork, true);
            }
        }
    }

    /**
     * Return version of product (Fuse, Fabric8) used.
     * @param product
     * @return
     */
    private String determineVersion(String product) {
        return determineVersion(karafHome, product);
    }

    /**
     * Return version of product (Fuse, Fabric8) used, but probably based on different karafHome
     * @param home
     * @param product
     * @return
     */
    private String determineVersion(File home, String product) {
        if (env != EnvType.FABRIC_CHILD && env != EnvType.STANDALONE_CHILD) {
            File versions = new File(home, "fabric/import/fabric/profiles/default.profile/io.fabric8.version.properties");
            if (versions.exists() && versions.isFile()) {
                Properties props = new Properties();
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(versions);
                    props.load(fis);
                    return props.getProperty(product);
                } catch (IOException e) {
                    Activator.log(LogService.LOG_ERROR, null, e.getMessage(), e, true);
                    return null;
                } finally {
                    IOUtils.closeQuietly(fis);
                }
            } else {
                Activator.log2(LogService.LOG_ERROR, "Can't find io.fabric8.version.properties file in default profile");
            }
        } else {
            // for child container we have to be more careful and not examine root container's io.fabric8.version.properties!
            File startup = new File(home, "etc/startup.properties");
            if (startup.exists() && startup.isFile()) {
                Properties props = new Properties();
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(startup);
                    props.load(fis);
                    for (String key : props.stringPropertyNames()) {
                        if (key.startsWith("org/apache/karaf/features/org.apache.karaf.features.core")) {
                            String url = Utils.pathToMvnurl(key);
                            Artifact artifact = Utils.mvnurlToArtifact(url, true);
                            if (artifact != null) {
                                return artifact.getVersion();
                            }
                        }
                    }
                } catch (IOException e) {
                    Activator.log(LogService.LOG_ERROR, null, e.getMessage(), e, true);
                    return null;
                } finally {
                    IOUtils.closeQuietly(fis);
                }
            } else {
                Activator.log2(LogService.LOG_ERROR, "Can't find etc/startup.properties file in child container");
            }

        }
        return null;
    }

    /**
     * Adds baseline distribution to the repository. It serves a different purpose in fabric and standalone scenarios.
     * In fabric mode, we put another baseline tag for root container when it is upgraded to specific version. The
     * versioning of root container is performed on another branch.
     * In standalone mode mode, the same branch is used to track baselines and for versioning of the container itself.
     * In standalone mode, patches are added to separate branch each.
     * In fabric mode, patches are added to baseline branches and each container has it's own versioning branch
     * (private, not pushed to main repository).
     * @param git non-bare repository to perform the operation with correct branch checked out already
     */
    private RevCommit trackBaselineRepository(Git git) throws IOException, GitAPIException {
        // initialize repo with baseline version and push to reference repo
        String currentFuseVersion = determineVersion("fuse");
        String currentFabricVersion = determineVersion("fabric");

        // check what product are we in
        File systemRepo = getSystemRepository(karafHome, systemContext);
        File baselineDistribution = null;

        String baselineLocation = Utils.getBaselineLocationForProduct(karafHome, systemContext, currentFuseVersion);
        if (baselineLocation != null) {
            baselineDistribution = new File(patchesDir, baselineLocation);
        } else {
            // do some guessing - first JBoss Fuse, then JBoss A-MQ
            String[] locations = new String[] {
                    systemRepo.getCanonicalPath() + "/org/jboss/fuse/jboss-fuse-full/%1$s/jboss-fuse-full-%1$s-baseline.zip",
                    patchesDir.getCanonicalPath() + "/jboss-fuse-full-%1$s-baseline.zip",
                    systemRepo.getCanonicalPath() + "/org/jboss/amq/jboss-a-mq/%s/jboss-a-mq-%1$s-baseline.zip",
                    patchesDir.getCanonicalPath() + "/jboss-a-mq-%1$s-baseline.zip"
            };

            for (String location : locations) {
                location = String.format(location, currentFuseVersion);
                if (new File(location).isFile()) {
                    baselineDistribution = new File(location);
                    Activator.log(LogService.LOG_INFO, "Found baseline distribution: " + baselineDistribution.getCanonicalPath());
                    break;
                }
            }
        }
        if (baselineDistribution != null) {
            return trackBaselineRepository(git, baselineDistribution, currentFuseVersion);
        } else {
            String message = "Can't find baseline distribution in patches dir or inside system repository.";
            Activator.log2(LogService.LOG_WARNING, message);
            throw new PatchException(message);
        }
    }

    /**
     * Tracks all baselines for child containers that weren't tracked already. These are looked up inside
     * <code>system/org/apache/karaf/admin/org.apache.karaf.admin.core/**</code>
     * @param fork
     */
    private void trackBaselinesForChildContainers(Git fork) throws IOException, GitAPIException {
        if (fork.getRepository().getRef("refs/heads/" + gitPatchRepository.getChildBranchName()) == null) {
            // checkout patches-child branch - it'll track baselines for fabric:container-create-child containers
            String startPoint = "patch-management^{commit}";
            if (fork.getRepository().getRef("refs/remotes/origin/" + gitPatchRepository.getChildBranchName()) != null) {
                startPoint = "refs/remotes/origin/" + gitPatchRepository.getChildBranchName();
            }
            gitPatchRepository.checkout(fork)
                    .setName(gitPatchRepository.getChildBranchName())
                    .setStartPoint(startPoint)
                    .setCreateBranch(true)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .call();
        } else {
            gitPatchRepository.checkout(fork)
                    .setName(gitPatchRepository.getChildBranchName())
                    .call();
        }

        File systemRepo = getSystemRepository(karafHome, systemContext);
        File[] versionDirs = new File(systemRepo, "org/apache/karaf/admin/org.apache.karaf.admin.core").listFiles();
        Set<Version> versions = new TreeSet<>();

        if (versionDirs != null) {
            for (File version : versionDirs) {
                if (version.isDirectory()) {
                    versions.add(Utils.getOsgiVersion(version.getName()));
                }
            }
        }
        for (Version v : versions) {
            String karafVersion = v.toString();
            String tagName = String.format(EnvType.FABRIC_CHILD.getBaselineTagFormat(), karafVersion);

            if (gitPatchRepository.containsTag(fork, tagName)) {
                continue;
            }

            File baselineDistribution = null;
            String location = String.format(systemRepo.getCanonicalPath() + "/org/apache/karaf/admin/org.apache.karaf.admin.core/%1$s/org.apache.karaf.admin.core-%1$s.jar", karafVersion);
            if (new File(location).isFile()) {
                baselineDistribution = new File(location);
                Activator.log(LogService.LOG_INFO, "Found child baseline distribution: " + baselineDistribution.getCanonicalPath());
            }

            if (baselineDistribution != null) {
                try {
                    unzipKarafAdminJar(baselineDistribution, fork.getRepository().getWorkTree());

                    fork.add()
                            .addFilepattern(".")
                            .call();
                    RevCommit commit = gitPatchRepository.prepareCommit(fork,
                            String.format(MARKER_BASELINE_CHILD_COMMIT_PATTERN, karafVersion))
                            .call();

                    // and we'll tag the child baseline
                    fork.tag()
                            .setName(tagName)
                            .setObjectId(commit)
                            .call();

                } catch (Exception e) {
                    Activator.log(LogService.LOG_ERROR, null, e.getMessage(), e, true);
                }
            }
        }

        gitPatchRepository.push(fork, gitPatchRepository.getChildBranchName());
    }

    /**
     * Unzips <code>bin</code> and <code>etc</code> from org.apache.karaf.admin.core.
     * @param artifact
     * @param targetDirectory
     * @throws IOException
     */
    private void unzipKarafAdminJar(File artifact, File targetDirectory) throws IOException {
        ZipFile zf = new ZipFile(artifact);
        String prefix = "org/apache/karaf/admin/";
        try {
            for (Enumeration<ZipArchiveEntry> e = zf.getEntries(); e.hasMoreElements(); ) {
                ZipArchiveEntry entry = e.nextElement();
                String name = entry.getName();
                if (!name.startsWith(prefix)) {
                    continue;
                }
                name = name.substring(prefix.length());
                if (!name.startsWith("bin") && !name.startsWith("etc")) {
                    continue;
                }
                // flags from karaf.admin.core
                // see: org.apache.karaf.admin.internal.AdminServiceImpl.createInstance()
                boolean windows = System.getProperty("os.name").startsWith("Win");
                boolean cygwin = windows && new File(System.getProperty("karaf.home"), "bin/admin").exists();

                if (!entry.isDirectory() && !entry.isUnixSymlink()) {
                    if (windows && !cygwin) {
                        if (name.startsWith("bin/") && !name.endsWith(".bat")) {
                            continue;
                        }
                    } else {
                        if (name.startsWith("bin/") && name.endsWith(".bat")) {
                            continue;
                        }
                    }
                    File file = new File(targetDirectory, name);
                    file.getParentFile().mkdirs();
                    FileOutputStream output = new EOLFixingFileOutputStream(targetDirectory, file);
                    IOUtils.copyLarge(zf.getInputStream(entry), output);
                    IOUtils.closeQuietly(output);
                    if (Files.getFileAttributeView(file.toPath(), PosixFileAttributeView.class) != null) {
                        if (name.startsWith("bin/") && !name.endsWith(".bat")) {
                            Files.setPosixFilePermissions(file.toPath(), getPermissionsFromUnixMode(file, 0775));
                        }
                    }
                }
            }
        } finally {
            if (zf != null) {
                zf.close();
            }
        }
    }

    /**
     * SSH containers are created from io.fabric8:fabric8-karaf distros. These however may be official, foundation
     * or random (ZIPped from what currently was in FUSE_HOME of the container that created SSH container.
     * Track all new baselines for SSH containers. These are looked up inside
     * <code>io/fabric8/fabric8-karaf/**</code>
     * @param fork
     */
    public void trackBaselinesForSSHContainers(Git fork) throws IOException, GitAPIException {
        // two separate branches for two kinds of baselines for SSH containers
        if (fork.getRepository().getRef("refs/heads/" + gitPatchRepository.getFuseSSHContainerPatchBranchName()) == null) {
            String startPoint = "patch-management^{commit}";
            if (fork.getRepository().getRef("refs/remotes/origin/" + gitPatchRepository.getFuseSSHContainerPatchBranchName()) != null) {
                startPoint = "refs/remotes/origin/" + gitPatchRepository.getFuseSSHContainerPatchBranchName();
            }
            gitPatchRepository.checkout(fork)
                    .setName(gitPatchRepository.getFuseSSHContainerPatchBranchName())
                    .setStartPoint(startPoint)
                    .setCreateBranch(true)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .call();
        }
        if (fork.getRepository().getRef("refs/heads/" + gitPatchRepository.getFabric8SSHContainerPatchBranchName()) == null) {
            String startPoint = "patch-management^{commit}";
            if (fork.getRepository().getRef("refs/remotes/origin/" + gitPatchRepository.getFabric8SSHContainerPatchBranchName()) != null) {
                startPoint = "refs/remotes/origin/" + gitPatchRepository.getFabric8SSHContainerPatchBranchName();
            }
            gitPatchRepository.checkout(fork)
                    .setName(gitPatchRepository.getFabric8SSHContainerPatchBranchName())
                    .setStartPoint(startPoint)
                    .setCreateBranch(true)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .call();
        }

        File systemRepo = getSystemRepository(karafHome, systemContext);
        File[] versionDirs = new File(systemRepo, "io/fabric8/fabric8-karaf").listFiles();
        Set<Version> versions = new TreeSet<>();

        if (versionDirs != null) {
            for (File version : versionDirs) {
                if (version.isDirectory()) {
                    versions.add(Utils.getOsgiVersion(version.getName()));
                }
            }
        }
        for (Version v : versions) {
            String fabric8Version = v.toString();

            // each version may have two (maybe more?) baseline distros.
            // we may have "official" fabric8-karaf and the one created from FUSE_HOME (zipped on-fly).

            String[] artifactPatterns = new String[] {
                    "fabric8-karaf-%1$s.zip",
                    "fabric8-karaf-%1$s-custom.zip"
            };

            for (String artifactPattern : artifactPatterns) {
                File baselineDistribution = null;
                String location = String.format(systemRepo.getCanonicalPath() + "/io/fabric8/fabric8-karaf/%1$s/" + artifactPattern, fabric8Version);

                if (new File(location).isFile()) {
                    baselineDistribution = new File(location);
                    Activator.log(LogService.LOG_INFO, "Found SSH baseline distribution: " + baselineDistribution.getCanonicalPath());
                }

                if (baselineDistribution != null) {
                    try {
                        // we don't know yet which branch we have to checkout, this method will do 2 pass unzipping
                        // and leave the fork checked out to correct branch - its name will be returned
                        String rootDir = String.format("fabric8-karaf-%s", fabric8Version);
                        String branchName = unzipFabric8Distro(rootDir, baselineDistribution, fabric8Version, fork);

                        if (branchName == null) {
                            continue;
                        }

                        // remove the deletes
                        for (String missing : fork.status().call().getMissing()) {
                            fork.rm().addFilepattern(missing).call();
                        }

                        // and we'll tag the child baseline
                        String tagName = branchName.replace("patches-", "");

                        fork.add()
                                .addFilepattern(".")
                                .call();
                        RevCommit commit = gitPatchRepository.prepareCommit(fork,
                                String.format(MARKER_BASELINE_SSH_COMMIT_PATTERN, tagName, fabric8Version))
                                .call();

                        fork.tag()
                                .setName(String.format("baseline-%s-%s", tagName, fabric8Version))
                                .setObjectId(commit)
                                .call();
                    } catch (Exception e) {
                        Activator.log(LogService.LOG_ERROR, null, e.getMessage(), e, true);
                    }
                }
            }
        }

        gitPatchRepository.push(fork, gitPatchRepository.getFuseSSHContainerPatchBranchName());
        gitPatchRepository.push(fork, gitPatchRepository.getFabric8SSHContainerPatchBranchName());
    }

    /**
     * Unzips <code>bin</code> and <code>etc</code> everything we need from org.apache.karaf.admin.core.
     * @param rootDir
     * @param artifact
     * @param version
     * @param fork
     * @return branch name where the distro should be tracked, or <code>null</code> if it's already tracked
     * @throws IOException
     */
    private String unzipFabric8Distro(String rootDir, File artifact, String version, Git fork) throws IOException, GitAPIException {
        ZipFile zf = new ZipFile(artifact);
        try {
            // first pass - what's this distro?
            boolean officialFabric8 = true;
            for (Enumeration<ZipArchiveEntry> e = zf.getEntries(); e.hasMoreElements(); ) {
                ZipArchiveEntry entry = e.nextElement();
                String name = entry.getName();
                if (name.startsWith(rootDir + "/")) {
                    name = name.substring(rootDir.length() + 1);
                }
                if ("etc/startup.properties".equals(name)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    IOUtils.copyLarge(zf.getInputStream(entry), baos);
                    Properties props = new Properties();
                    props.load(new ByteArrayInputStream(baos.toByteArray()));
                    for (String p : props.stringPropertyNames()) {
                        if (p.startsWith("org/jboss/fuse/shared-commands/")) {
                            // we have Fuse!
                            officialFabric8 = false;
                            break;
                        }
                    }
                }
            }
            // checkout correct branch
            if (officialFabric8) {
                if (gitPatchRepository.containsTag(fork, String.format("baseline-ssh-fabric8-%s", version))) {
                    return null;
                }
                gitPatchRepository.checkout(fork)
                        .setName(gitPatchRepository.getFabric8SSHContainerPatchBranchName())
                        .call();
            } else {
                if (gitPatchRepository.containsTag(fork, String.format("baseline-ssh-fuse-%s", version))) {
                    return null;
                }
                gitPatchRepository.checkout(fork)
                        .setName(gitPatchRepository.getFuseSSHContainerPatchBranchName())
                        .call();
            }

            for (String managedDirectory : MANAGED_DIRECTORIES) {
                FileUtils.deleteDirectory(new File(fork.getRepository().getWorkTree(), managedDirectory));
            }

            // second pass - unzip what we need
            for (Enumeration<ZipArchiveEntry> e = zf.getEntries(); e.hasMoreElements(); ) {
                ZipArchiveEntry entry = e.nextElement();
                String name = entry.getName();
                if (name.startsWith(rootDir + "/")) {
                    name = name.substring(rootDir.length() + 1);
                }
                if (!(name.startsWith("bin")
                        || name.startsWith("etc")
                        || name.startsWith("fabric")
                        || name.startsWith("lib")
                        || name.startsWith("licenses")
                        || name.startsWith("metatype"))) {
                    continue;
                }
                if (!entry.isDirectory() && !entry.isUnixSymlink()) {
                    File file = new File(fork.getRepository().getWorkTree(), name);
                    file.getParentFile().mkdirs();
                    FileOutputStream output = new EOLFixingFileOutputStream(fork.getRepository().getWorkTree(), file);
                    IOUtils.copyLarge(zf.getInputStream(entry), output);
                    IOUtils.closeQuietly(output);
                    if (Files.getFileAttributeView(file.toPath(), PosixFileAttributeView.class) != null) {
                        if (name.startsWith("bin/") && !name.endsWith(".bat")) {
                            Files.setPosixFilePermissions(file.toPath(), getPermissionsFromUnixMode(file, 0775));
                        }
                    }
                }
            }

            return officialFabric8 ? gitPatchRepository.getFabric8SSHContainerPatchBranchName()
                    : gitPatchRepository.getFuseSSHContainerPatchBranchName();
        } finally {
            if (zf != null) {
                zf.close();
            }
        }
    }

    /**
     * These baselines are created from org.jboss.fuse:jboss-fuse-full or org.jboss.amq:jboss-a-mq baseline distros.
     * @param fork
     */
    private void trackBaselinesForRootContainer(Git fork) throws IOException, GitAPIException {
        // two separate branches for two kinds of baselines for root containers
        if (fork.getRepository().getRef("refs/heads/" + gitPatchRepository.getFuseRootContainerPatchBranchName()) == null) {
            String startPoint = "patch-management^{commit}";
            if (fork.getRepository().getRef("refs/remotes/origin/" + gitPatchRepository.getFuseRootContainerPatchBranchName()) != null) {
                startPoint = "refs/remotes/origin/" + gitPatchRepository.getFuseRootContainerPatchBranchName();
            }
            gitPatchRepository.checkout(fork)
                    .setName(gitPatchRepository.getFuseRootContainerPatchBranchName())
                    .setStartPoint(startPoint)
                    .setCreateBranch(true)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .call();
        }
        if (fork.getRepository().getRef("refs/heads/" + gitPatchRepository.getAmqRootContainerPatchBranchName()) == null) {
            String startPoint = "patch-management^{commit}";
            if (fork.getRepository().getRef("refs/remotes/origin/" + gitPatchRepository.getAmqRootContainerPatchBranchName()) != null) {
                startPoint = "refs/remotes/origin/" + gitPatchRepository.getAmqRootContainerPatchBranchName();
            }
            gitPatchRepository.checkout(fork)
                    .setName(gitPatchRepository.getAmqRootContainerPatchBranchName())
                    .setStartPoint(startPoint)
                    .setCreateBranch(true)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .call();
        }

        File systemRepo = getSystemRepository(karafHome, systemContext);
        String currentFuseVersion = determineVersion("fuse");
        String currentFabricVersion = determineVersion("fabric");

        // Fuse distros first
        File[] versionDirs = new File(systemRepo, "org/jboss/fuse/jboss-fuse-full").listFiles();
        Map<Version, File> versions = new TreeMap<>();

        gitPatchRepository.checkout(fork)
                .setName(gitPatchRepository.getFuseRootContainerPatchBranchName())
                .call();

        // we'll look for Fuse/AMQ baseline in /patches dir too - it doesn't have to be present
        versions.put(Utils.getOsgiVersion(currentFuseVersion), patchesDir);
        if (versionDirs != null) {
            for (File version : versionDirs) {
                if (version.isDirectory()) {
                    versions.put(Utils.getOsgiVersion(version.getName()), version);
                }
            }
        }
        for (Map.Entry<Version, File> entry : versions.entrySet()) {
            String version = entry.getKey().toString();
            if (gitPatchRepository.containsTag(fork, String.format(EnvType.FABRIC_FUSE.getBaselineTagFormat(), version))) {
                continue;
            }

            File baselineDistribution = null;
            File location = new File(entry.getValue(), String.format("jboss-fuse-full-%1$s-baseline.zip", version));
            if (location.isFile()) {
                baselineDistribution = location;
                Activator.log(LogService.LOG_INFO, "Found Fuse baseline distribution: " + baselineDistribution.getCanonicalPath());
            }

            if (baselineDistribution != null) {
                RevCommit commit = trackBaselineRepository(fork, baselineDistribution, version);
                fork.tag()
                        .setName(String.format(EnvType.FABRIC_FUSE.getBaselineTagFormat(), version))
                        .setObjectId(commit)
                        .call();
            }
        }

        // Now AMQ distros
        versionDirs = new File(systemRepo, "org/jboss/amq/jboss-a-mq").listFiles();
        versions.clear();

        gitPatchRepository.checkout(fork)
                .setName(gitPatchRepository.getAmqRootContainerPatchBranchName())
                .call();

        // we'll look for Fuse/AMQ baseline in /patches dir too - it doesn't have to be present
        versions.put(Utils.getOsgiVersion(currentFuseVersion), patchesDir);
        if (versionDirs != null) {
            for (File version : versionDirs) {
                if (version.isDirectory()) {
                    versions.put(Utils.getOsgiVersion(version.getName()), version);
                }
            }
        }
        for (Map.Entry<Version, File> entry : versions.entrySet()) {
            String version = entry.getKey().toString();
            if (gitPatchRepository.containsTag(fork, String.format(EnvType.FABRIC_AMQ.getBaselineTagFormat(), version))) {
                continue;
            }

            File baselineDistribution = null;
            File location = new File(entry.getValue(), String.format("jboss-a-mq-%1$s-baseline.zip", version));
            if (location.isFile()) {
                baselineDistribution = location;
                Activator.log(LogService.LOG_INFO, "Found AMQ baseline distribution: " + baselineDistribution.getCanonicalPath());
            }

            if (baselineDistribution != null) {
                RevCommit commit = trackBaselineRepository(fork, baselineDistribution, version);
                fork.tag()
                        .setName(String.format(EnvType.FABRIC_AMQ.getBaselineTagFormat(), version))
                        .setObjectId(commit)
                        .call();
            }
        }

        gitPatchRepository.push(fork, gitPatchRepository.getFuseRootContainerPatchBranchName());
        gitPatchRepository.push(fork, gitPatchRepository.getAmqRootContainerPatchBranchName());
    }

    /**
     * Adds the content of baseline Fuse/AMQ distribution to git repository.
     * These are the "biggest" baselines - we also do etc/overrides.properties reset.
     * @param git
     * @param baselineDistribution
     * @param version
     * @throws IOException
     * @throws GitAPIException
     */
    private RevCommit trackBaselineRepository(Git git, File baselineDistribution, String version) throws IOException, GitAPIException {
        for (String managedDirectory : MANAGED_DIRECTORIES) {
            FileUtils.deleteDirectory(new File(git.getRepository().getWorkTree(), managedDirectory));
        }
        unpack(baselineDistribution, git.getRepository().getWorkTree(), 1);

        String fabricVersion = determineVersion(git.getRepository().getWorkTree(), "fabric");

        git.add()
                .addFilepattern(".")
                .call();
        // remove the deletes (without touching specially-managed etc/overrides.properties)
        for (String missing : git.status().call().getMissing()) {
            git.rm().addFilepattern(missing).call();
        }
        gitPatchRepository.prepareCommit(git, String.format(MARKER_BASELINE_COMMIT_PATTERN, version)).call();

        if (!env.isFabric()) {
            // let's replace the reference to "patch" feature repository, to be able to do rollback to this very first
            // baseline
            File featuresCfg = new File(git.getRepository().getWorkTree(), "etc/org.apache.karaf.features.cfg");
            if (featuresCfg.isFile()) {
                boolean needsRewriting = false;
                List<String> lines = FileUtils.readLines(featuresCfg);
                List<String> newVersion = new LinkedList<>();
                for (String line : lines) {
                    if (!line.contains("mvn:io.fabric8.patch/patch-features/") || fabricVersion == null) {
                        newVersion.add(line);
                    } else {
                        // use version of current bundle, so rolling back to that baseline won't bring us
                        // to old patch management
                        String newLine = line.replace(fabricVersion, bundleContext.getBundle().getVersion().toString());
                        newVersion.add(newLine);
                        if (!line.equals(newLine)) {
                            needsRewriting = true;
                        }
                    }
                }
                if (needsRewriting) {
                    StringBuilder sb = new StringBuilder();
                    for (String newLine : newVersion) {
                        sb.append(newLine).append("\n");
                    }
                    FileUtils.write(featuresCfg, sb.toString());
                    git.add()
                            .addFilepattern("etc/org.apache.karaf.features.cfg")
                            .call();
                    gitPatchRepository.prepareCommit(git, String.format(MARKER_BASELINE_REPLACE_PATCH_FEATURE_PATTERN,
                            version, bundleContext.getBundle().getVersion().toString())).call();

                    // let's assume that user didn't change this file and replace it with our version
                    FileUtils.copyFile(featuresCfg,
                            new File(karafBase, "etc/org.apache.karaf.features.cfg"));
                }
            }
        }

        // each baseline ships new feature repositories and from this point (or the point where new rollup patch
        // is installed) we should start with 0-sized overrides.properties in order to have easier non-rollup
        // patch installation - no P-patch should ADD overrides.properties - they have to only MODIFY it because
        // it's easier to revert such modification (in case P-patch is rolled back - probably in different order
        // than it was installed)
        resetOverrides(git.getRepository().getWorkTree());
        git.add().addFilepattern("etc/overrides.properties").call();
        return gitPatchRepository.prepareCommit(git,
                String.format(MARKER_BASELINE_RESET_OVERRIDES_PATTERN, version)).call();
    }

    /**
     * Update private history tracking branch in fabric env or for standalone child container - genreally
     * when patch management is done in another container. This method is called when it's needed, not every time
     * patch-management bundle is started/stopped/updated.
     * Method <strong>always</strong> changes private history branch and align current version
     * @param fork
     */
    private void ensureCorrectContainerHistory(Git fork, String version) throws IOException, GitAPIException {
        if (fork.getRepository().getRef("refs/heads/" + gitPatchRepository.getMainBranchName()) == null) {
            String startPoint = "patch-management^{commit}";
            if (fork.getRepository().getRef("refs/remotes/origin/" + gitPatchRepository.getMainBranchName()) != null) {
                startPoint = "refs/remotes/origin/" + gitPatchRepository.getMainBranchName();
            }
            gitPatchRepository.checkout(fork)
                    .setName(gitPatchRepository.getMainBranchName())
                    .setStartPoint(startPoint)
                    .setCreateBranch(true)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .call();
        } else {
            gitPatchRepository.checkout(fork)
                    .setName(gitPatchRepository.getMainBranchName())
                    .call();
        }

        // we may still be on the baseline from the time before fabric:create!

        if (version == null) {
            // align to version determined from environment
            version = determineVersion(env.getProductId());
        }

        // user changes in history
        ObjectId since = fork.getRepository().resolve("patch-management^{commit}");
        // we'll pick all user changes between baseline and main patch branch without P installations
        ObjectId to = fork.getRepository().resolve(gitPatchRepository.getMainBranchName() + "^{commit}");
        Iterable<RevCommit> mainChanges = fork.log().addRange(since, to).call();
        List<RevCommit> userChanges = new LinkedList<>();
        for (RevCommit rc : mainChanges) {
            if (isUserChangeCommit(rc)) {
                userChanges.add(rc);
            }
        }

        // let's rewrite history
        fork.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(String.format(env.getBaselineTagFormat(), version))
                .call();

        // and pick up user changes just like we'd install Rollup patch

        // reapply those user changes that are not conflicting
        // for each conflicting cherry-pick we do a backup of user files, to be able to restore them
        // when rollup patch is rolled back
        ListIterator<RevCommit> it = userChanges.listIterator(userChanges.size());
        int prefixSize = Integer.toString(userChanges.size()).length();
        int count = 1;

        // we may have unadded changes - when file mode is changed
        fork.reset().setMode(ResetCommand.ResetType.HARD).call();

        while (it.hasPrevious()) {
            RevCommit userChange = it.previous();
            String prefix = String.format("%0" + prefixSize + "d-%s", count++, userChange.getName());
            CherryPickResult result = fork.cherryPick()
                    .include(userChange)
                    .setNoCommit(true)
                    .call();
            // no backup (!?)
            handleCherryPickConflict(null, fork, result, userChange, false, PatchKind.ROLLUP, null, false);

            gitPatchRepository.prepareCommit(fork, userChange.getFullMessage()).call();

            // we may have unadded changes - when file mode is changed
            fork.reset().setMode(ResetCommand.ResetType.HARD).call();
        }

        gitPatchRepository.push(fork, gitPatchRepository.getMainBranchName());
    }

    /**
     * <p>Applies existing user changes in ${karaf.home}/{bin,etc,fabric,lib,licenses,metatype} directories to patch
     * management Git repository, doesn't modify ${karaf.home}</p>
     * <p>TODO: Maybe we should ask user whether the change was intended or not? blacklist some changes?</p>
     * @param git non-bare repository to perform the operation
     */
    public void applyUserChanges(Git git) throws GitAPIException, IOException {
        File wcDir = git.getRepository().getDirectory().getParentFile();

        try {
            // let's simply copy all user files on top of git working copy
            // then we can check the differences simply by committing the changes
            // there should be no conflicts, because we're not merging anything
            // "true" would mean that target dir is first deleted to detect removal of files
            copyManagedDirectories(karafBase, wcDir, false, true, false);

            // commit the changes to main repository
            Status status = git.status().call();
            if (!status.isClean()) {
                boolean amend = false;
                if (status.getUntracked().size() == 0
                        && status.getMissing().size() == 0
                        && status.getModified().size() == 1) {
                    if ("etc/io.fabric8.mq.fabric.server-broker.cfg".equals(status.getModified().iterator().next())) {
                        amend = true;
                    }
                }
                Activator.log(LogService.LOG_INFO, (amend ? "Amending" : "Storing") + " user changes");

                git.add()
                        .addFilepattern(".")
                        .call();

                // let's not do removals when tracking user changes. if we do, cherry-picking user changes over
                // a rollup patch that introduced new file would simply remove it
//                for (String name : status.getMissing()) {
//                    git.rm().addFilepattern(name).call();
//                }

                gitPatchRepository.prepareCommit(git, MARKER_USER_CHANGES_COMMIT)
                        .setAmend(amend)
                        .call();
                gitPatchRepository.push(git);

                // now main repository has exactly the same content as ${karaf.home}
                // We have two methods of synchronization wrt future rollup changes:
                // 1. we can pull from "origin" in the MAIN working copy (${karaf.hoome}) (making sure the copy is initialized)
                // 2. we can apply rollup patch to temporary fork + working copy, perform merges, resolve conflicts, etc
                //    and if everything goes fine, simply override ${karaf.hoome} content with the working copy content
                // method 2. doesn't require real working copy and ${karaf.home}/.git directory
            } else {
                Activator.log(LogService.LOG_INFO, "No user changes detected");
            }
        } catch (GitAPIException | IOException e) {
            Activator.log(LogService.LOG_ERROR, null, e.getMessage(), e, true);
        }
    }

    /**
     * Copy content of managed directories from source (like ${karaf.home}) to target (e.g. working copy of git repository)
     * @param sourceDir
     * @param targetDir
     * @param removeTarget whether to delete content of targetDir/<em>managedDirectory</em> first (helpful to detect removals from source)
     * @param onlyModified whether to copy only modified files (to preserve modification time when target file is
     * not changed)
     * @param useLibNext whether to rename lib to lib.next during copy
     * @throws IOException
     */
    private void copyManagedDirectories(File sourceDir, File targetDir, boolean removeTarget, boolean onlyModified, boolean useLibNext) throws IOException {
        for (String dir : MANAGED_DIRECTORIES) {
            File managedSrcDir = new File(sourceDir, dir);
            if (!managedSrcDir.exists()) {
                continue;
            }
            File destDir = new File(targetDir, dir);
            if (useLibNext && "lib".equals(dir)) {
                destDir = new File(targetDir, "lib.next");
                if (removeTarget) {
                    FileUtils.deleteQuietly(destDir);
                }
                FileUtils.copyDirectory(managedSrcDir, destDir);
            } else {
                if (removeTarget) {
                    FileUtils.deleteQuietly(destDir);
                }
                EOLFixingFileUtils.copyDirectory(managedSrcDir, targetDir, destDir, onlyModified);
            }
            if ("bin".equals(dir)) {
                // repair file permissions
                File[] files = destDir.listFiles();
                if (files != null) {
                    for (File script : files) {
                        if (!script.getName().endsWith(".bat")) {
                            if (Files.getFileAttributeView(script.toPath(), PosixFileAttributeView.class) != null) {
                                Files.setPosixFilePermissions(script.toPath(), getPermissionsFromUnixMode(script, 0775));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Assuming there's no track of adding patch-management bundle to etc/startup.properties, we also have to
     * actually <em>install</em> this bundle in ${karaf.home}/system
     * @param git non-bare repository to perform the operation
     */
    private RevCommit installPatchManagementBundle(Git git) throws IOException, GitAPIException {
        // if user simply osgi:install the patch-management bundle then adding the mvn: URI to etc/startup.properties is enough
        // but it patch-management was dropped into deploy/, we have to copy it to ${karaf.default.repository}
        File fileinstallDeployDir = getDeployDir(karafHome);
        String bundleVersion = bundleContext.getBundle().getVersion().toString();
        String patchManagementArtifact = String.format("patch-management-%s.jar", bundleVersion);
        File deployedPatchManagement = new File(fileinstallDeployDir, patchManagementArtifact);
        if (deployedPatchManagement.exists() && deployedPatchManagement.isFile()) {
            // let's copy it to system/
            File systemRepo = getSystemRepository(karafHome, systemContext);
            String targetFile = String.format("io/fabric8/patch/patch-management/%s/patch-management-%s.jar", bundleVersion, bundleVersion);
            File target = new File(systemRepo, targetFile);
            target.getParentFile().mkdirs();
            FileUtils.copyFile(deployedPatchManagement, target);
            // don't delete source artifact, because fileinstall can decide to uninstall the bundle!
            // we will do it in cleanupDeployDir()
        }

        bundleContext.getBundle().adapt(BundleStartLevel.class).setStartLevel(Activator.PATCH_MANAGEMENT_START_LEVEL);

        return replacePatchManagementBundleInStartupPropertiesIfNecessary(git, bundleVersion);
    }

    /**
     * One stop method that does everything related to installing patch-management bundle in etc/startup.properties.
     * It removes old version of the bundle, doesn't do anything if the bundle is already there and appends a declaration if there was none.
     * @param git
     * @param bundleVersion
     * @throws IOException
     * @throws GitAPIException
     */
    private RevCommit replacePatchManagementBundleInStartupPropertiesIfNecessary(Git git, String bundleVersion) throws IOException, GitAPIException {
        boolean modified = false;
        boolean installed = false;

        File etcStartupProperties = new File(git.getRepository().getDirectory().getParent(), "etc/startup.properties");
        List<String> lines = FileUtils.readLines(etcStartupProperties);
        List<String> newVersion = new LinkedList<>();
        for (String line : lines) {
            if (!line.startsWith("io/fabric8/patch/patch-management/")) {
                // copy unchanged
                newVersion.add(line);
            } else {
                // is it old, same, (newer??) version?
                Matcher matcher = VERSION_PATTERN.matcher(line);
                if (matcher.find()) {
                    // it should match
                    String alreadyInstalledVersion = matcher.group(1);
                    Version v1 = Utils.getOsgiVersion(alreadyInstalledVersion);
                    Version v2 = Utils.getOsgiVersion(bundleVersion);
                    if (v1.equals(v2)) {
                        // already installed at correct version
                        installed = true;
                    } else if (v1.compareTo(v2) < 0) {
                        // we'll install new version
                        modified = true;
                    } else {
                        // newer installed? why?
                    }
                }
            }
        }
        if (modified || !installed) {
            newVersion.add("");
            newVersion.add("# installed by patch-management");
            newVersion.add(String.format("io/fabric8/patch/patch-management/%s/patch-management-%s.jar=%d",
                    bundleVersion, bundleVersion, Activator.PATCH_MANAGEMENT_START_LEVEL));

            StringBuilder sb = new StringBuilder();
            for (String newLine : newVersion) {
                sb.append(newLine).append("\n");
            }
            FileUtils.write(new File(git.getRepository().getDirectory().getParent(), "etc/startup.properties"), sb.toString());

            // now to git working copy
            git.add()
                    .addFilepattern("etc/startup.properties")
                    .call();

            RevCommit commit = gitPatchRepository
                    .prepareCommit(git, String.format(MARKER_PATCH_MANAGEMENT_INSTALLATION_COMMIT_PATTERN, bundleVersion))
                    .call();

            // "checkout" the above change in main "working copy" (${karaf.home})
            applyChanges(git, commit.getParent(0), commit);

            Activator.log(LogService.LOG_INFO, String.format("patch-management-%s.jar installed in etc/startup.properties.", bundleVersion));

            return commit;
        }

        return null;
    }

    /**
     * <p>This method updates ${karaf.base} simply by copying all files from currently checked out working copy
     * (usually HEAD of main patch branch) to <code>${karaf.base}</code></p>
     * @param git
     * @param restartFileInstall whether to start fileinstall bundle at the end
     * @throws IOException
     * @throws GitAPIException
     */
    private void applyChanges(Git git, boolean restartFileInstall) throws IOException, GitAPIException {
        Bundle fileInstall = null;
        for (Bundle b : systemContext.getBundles()) {
            if (b.getSymbolicName() != null
                    && Utils.stripSymbolicName(b.getSymbolicName()).equals("org.apache.felix.fileinstall")) {
                fileInstall = b;
                break;
            }
        }

        if (fileInstall != null) {
            try {
                fileInstall.stop(Bundle.STOP_TRANSIENT);
            } catch (Exception e) {
                Activator.log(LogService.LOG_WARNING, e.getMessage());
            }
        }

        File wcDir = git.getRepository().getWorkTree();
        copyManagedDirectories(wcDir, karafBase, true, true, true);
        File lib = new File(wcDir, "lib");
        if (lib.exists()) {
            FileUtils.copyDirectory(lib, new File(karafBase, "lib.next"));
        }
        // we do exception for etc/overrides.properties
        File overrides = new File(karafBase, "etc/overrides.properties");
        if (overrides.exists() && overrides.length() == 0) {
            FileUtils.deleteQuietly(overrides);
        }

        if (restartFileInstall && fileInstall != null) {
            try {
                fileInstall.start(Bundle.START_TRANSIENT);
            } catch (Exception e) {
                Activator.log(LogService.LOG_WARNING, e.getMessage());
            }
        }
    }

    /**
     * <p>This method takes a range of commits (<code>c1..c2</code>) and performs manual update to ${karaf.home}.
     * If ${karaf.home} was also a checked out working copy, it'd be a matter of <code>git pull</code>. We may consider
     * this implementation, but now I don't want to keep <code>.git</code> directory in ${karaf.home}. Also, jgit
     * doesn't support <code>.git</code> <em>platform agnostic symbolic link</em>
     * (see: <code>git init --separate-git-dir</code>)</p>
     * <p>We don't have to fetch data from repository blobs, because <code>git</code> still points to checked-out
     * working copy</p>
     * <p>TODO: maybe we just have to copy <strong>all</strong> files from working copy to ${karaf.home}?</p>
     * @param git
     * @param commit1
     * @param commit2
     */
    private void applyChanges(Git git, RevCommit commit1, RevCommit commit2) throws IOException, GitAPIException {
        File wcDir = git.getRepository().getWorkTree();

        List<DiffEntry> diff = this.gitPatchRepository.diff(git, commit1, commit2);

        // Changes to the lib dir get done in the lib.next directory.  Lets copy
        // the lib dir just in case we do have modification to it.
        File lib = new File(karafBase, "lib");
        if (lib.isDirectory()) {
            FileUtils.copyDirectory(lib, new File(karafBase, "lib.next"));
        }
        boolean libDirectoryChanged = false;

        for (DiffEntry de : diff) {
            DiffEntry.ChangeType ct = de.getChangeType();
            /*
             * old path:
             *  - file add: always /dev/null
             *  - file modify: always getNewPath()
             *  - file delete: always the file being deleted
             *  - file copy: source file the copy originates from
             *  - file rename: source file the rename originates from
             * new path:
             *  - file add: always the file being created
             *  - file modify: always getOldPath()
             *  - file delete: always /dev/null
             *  - file copy: destination file the copy ends up at
             *  - file rename: destination file the rename ends up at
             */
            String newPath = de.getNewPath();
            String oldPath = de.getOldPath();
            switch (ct) {
                case ADD:
                case MODIFY:
                    Activator.log(LogService.LOG_DEBUG, "[PATCH-change] Modifying " + newPath);
                    String targetPath = newPath;
                    if (newPath.startsWith("lib/")) {
                        targetPath = "lib.next/" + newPath.substring(4);
                        libDirectoryChanged = true;
                    }
                    File srcFile = new File(wcDir, newPath);
                    File destFile = new File(karafBase, targetPath);
                    // we do exception for etc/overrides.properties
                    if ("etc/overrides.properties".equals(newPath) && srcFile.exists() && srcFile.length() == 0) {
                        FileUtils.deleteQuietly(destFile);
                    } else {
                        FileUtils.copyFile(srcFile, destFile);
                    }
                    break;
                case DELETE:
                    Activator.log(LogService.LOG_DEBUG, "[PATCH-change] Deleting " + newPath);
                    if (oldPath.startsWith("lib/")) {
                        oldPath = "lib.next/" + oldPath.substring(4);
                        libDirectoryChanged = true;
                    }
                    FileUtils.deleteQuietly(new File(karafBase, oldPath));
                    break;
                case COPY:
                case RENAME:
                    // not handled now
                    break;
            }
        }

        if (!libDirectoryChanged) {
            // lib.next directory might not be needed.
            FileUtils.deleteDirectory(new File(karafBase, "lib.next"));
        }
    }

    @Override
    public void cleanupDeployDir() throws IOException {
        Version ourVersion = bundleContext.getBundle().getVersion();
        File deploy = getDeployDir(karafHome);
        File[] deployedPatchManagementBundles = deploy.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return VERSION_PATTERN.matcher(name).matches();
            }
        });
        if (deployedPatchManagementBundles != null) {
            for (File anotherPatchManagementBundle : deployedPatchManagementBundles) {
                Matcher matcher = VERSION_PATTERN.matcher(anotherPatchManagementBundle.getName());
                matcher.find();
                String version = matcher.group(1);
                Version deployedVersion = Utils.getOsgiVersion(version);
                if (ourVersion.compareTo(deployedVersion) >= 0) {
                    Activator.log(LogService.LOG_INFO, "Deleting " + anotherPatchManagementBundle);
                    FileUtils.deleteQuietly(anotherPatchManagementBundle);
                }
            }
        }
    }

    @Override
    public void checkPendingPatches() {
        File[] pendingPatches = patchesDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.exists() && pathname.getName().endsWith(".pending");
            }
        });
        if (pendingPatches == null || pendingPatches.length == 0) {
            return;
        }

        final String dataCache = systemContext.getProperty("org.osgi.framework.storage");

        for (File pending : pendingPatches) {
            try {
                Pending what = Pending.valueOf(FileUtils.readFileToString(pending));
                final String prefix = what == Pending.ROLLUP_INSTALLATION ? "install" : "rollback";

                File patchFile = new File(pending.getParentFile(), pending.getName().replaceFirst("\\.pending$", ""));
                PatchData patchData = PatchData.load(new FileInputStream(patchFile));
                Patch patch = loadPatch(new PatchDetailsRequest(patchData.getId()));

                final File dataFilesBackupDir = new File(pending.getParentFile(), patchData.getId() + ".datafiles");
                final Properties backupProperties = new Properties();
                FileInputStream inStream = new FileInputStream(new File(dataFilesBackupDir, "backup-" + prefix + ".properties"));
                backupProperties.load(inStream);
                IOUtils.closeQuietly(inStream);

                // 1. we should have very few currently installed bundles (only from etc/startup.properties)
                //    and none of them is ACTIVE now, because we (patch-management) are at SL=2
                //    maybe one of those bundles has data directory to restore?
                for (Bundle b : systemContext.getBundles()) {
                    if (b.getSymbolicName() != null) {
                        String key = String.format("%s$$%s", stripSymbolicName(b.getSymbolicName()), b.getVersion().toString());
                        if (backupProperties.containsKey(key)) {
                            String backupDirName = backupProperties.getProperty(key);
                            File backupDir = new File(dataFilesBackupDir, prefix + "/" + backupDirName + "/data");
                            restoreDataDirectory(dataCache, b, backupDir);
                            // we no longer want to restore this dir
                            backupProperties.remove(key);
                        }
                    }
                }

                // 2. We can however have more bundle data backups - we'll restore them after each bundle
                //    is INSTALLED and we'll use listener for this
                BundleListener bundleListener = new SynchronousBundleListener() {
                    @Override
                    public void bundleChanged(BundleEvent event) {
                        Bundle b = event.getBundle();
                        if (event.getType() == BundleEvent.INSTALLED && b.getSymbolicName() != null) {
                            String key = String.format("%s$$%s", stripSymbolicName(b.getSymbolicName()), b.getVersion().toString());
                            if (backupProperties.containsKey(key)) {
                                String backupDirName = backupProperties.getProperty(key);
                                File backupDir = new File(dataFilesBackupDir, prefix + "/" + backupDirName + "/data");
                                restoreDataDirectory(dataCache, b, backupDir);
                            }
                        }
                    }
                };
                systemContext.addBundleListener(bundleListener);
                pendingPatchesListeners.put(patchData.getId(), bundleListener);
            } catch (Exception e) {
                Activator.log(LogService.LOG_ERROR, null, e.getMessage(), e, true);
            }
        }
    }

    @Override
    public String findLatestPatchRevision(File gitRepository, String versionId) {
        Git git = null;
        try {
            git = Git.open(gitRepository);

            Iterable<RevCommit> log = git.log().add(git.getRepository().resolve(versionId)).call();
            List<RevCommit> oldestToNewest = new LinkedList<>();
            RevCommit patchBaseRevision = null;
            for (RevCommit rc : log) {
                // in case we don't find previous R patch, we'll have to find it the hard way
                oldestToNewest.add(0, rc);
                if (rc.getShortMessage().startsWith("Installing rollup patch ")) {
                    patchBaseRevision = rc;
                    break;
                }
            }
            if (patchBaseRevision == null) {
                // we still don't know where to start patch branch for fabric profiles
                // normally, we start with "First Commit <user@fabric>", then, there's series of
                // "Imported from ..." and "Added profile zip(s) ..."
                // then there should be 2 (at least in 6.2.0+) commits that doesn't change anything (except
                // the order of properties) commits for "default" and "fabric" profiles - we'll start patching
                // from this change
                for (RevCommit rc : oldestToNewest) {
                    if (rc.getShortMessage().startsWith("Update configurations for profile: ")) {
                        patchBaseRevision = rc;
                        break;
                    }
                }
            }
            if (patchBaseRevision == null) {
                // we didn't find good place to start... we'll start from HEAD then
                return versionId;
            }

            String patchBranchName = String.format("__%s-%d", versionId, new Date().getTime());
            Ref branch = git.checkout()
                    .setCreateBranch(true)
                    .setName(patchBranchName)
                    .setStartPoint(patchBaseRevision)
                    .call();

            return patchBranchName;
        } catch (Exception e) {
            throw new PatchException(e.getMessage(), e);
        } finally {
            if (git != null) {
                gitPatchRepository.closeRepository(git, false);
            }
        }
    }

    @Override
    public void installProfiles(File gitRepository, String versionId, Patch patch, ProfileUpdateStrategy strategy) {
        // remember - we operate on totally different git repository!
        // we should have version branch already checked out.

        // here we don't merge/cherry-pick anything - we're preparing new commit simply by copying
        // one set of profiles (from R patch) over another (current profile definitions from Fabric)
        // we won't have merge conflicts, but we can't simply copy profiles over, because existing profiles
        // may have custom changes
        Git git = null;
        try {
            git = Git.open(gitRepository);

            File src = new File(patch.getPatchData().getPatchDirectory(), "fabric/import/fabric/profiles");
            File dst = new File(git.getRepository().getWorkTree(), "fabric/profiles");

            // ENTESB-6003:
            // let's clean the target directory first, so we can detect file removals in patches (like moving
            // jmx.* and org.apache.karaf.command.* PIDs from jboss-fuse-full to acls profile)
            FileUtils.deleteDirectory(dst);
            dst.mkdir();

            ProfileFileUtils.copyDirectory(src, dst, strategy);
            git.add().addFilepattern(".").call();
            // remove the deletes
            for (String missing : git.status().call().getMissing()) {
                git.rm().addFilepattern(missing).call();
            }

            // commit profile changes in patch branch - ultimate commit will be the merge commit
            git.commit()
                    .setMessage("Installing profiles from patch \"" + patch.getPatchData().getId() + "\"")
                    .call();

            PatchResult result = patch.getResult();
            if (result == null) {
                result = new PatchResult(patch.getPatchData(), false, new Date().getTime(), null, null);
                patch.setResult(result);
            }
            result.getVersions().add(versionId);
            result.store();
        } catch (Exception e) {
            throw new PatchException(e.getMessage(), e);
        } finally {
            if (git != null) {
                gitPatchRepository.closeRepository(git, false);
            }
        }
    }

    @Override
    public void mergeProfileChanges(Patch patch, File gitRepository, String versionBranch, String patchBranch) {
        Git git = null;
        try {
            git = Git.open(gitRepository);

            // merge version branch with patch branch - no fast forward, so we have nice named commit
            git.checkout()
                    .setCreateBranch(false)
                    .setName(versionBranch)
                    .call();

            MergeResult result = git.merge()
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                    .include(git.getRepository().resolve(patchBranch))
                    .setCommit(false)
                    .call();

            boolean commit = true;
            if (result.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                handleMergeConflict(git, result, versionBranch, patchBranch);
            } else if (result.getMergeStatus() != MergeResult.MergeStatus.MERGED_NOT_COMMITTED) {
                commit = false;
                Activator.log2(LogService.LOG_ERROR, "Can't merge version branch \"" + versionBranch + "\" with" +
                        " patch branch \"" + patchBranch + "\". Resetting the branch.");
                git.reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .call();
            }
//            if (commit) {
//                git.commit()
//                        .setMessage("Installing rollup patch \"" + patch.getPatchData().getId() + "\"")
//                        .call();
//            }
        } catch (Exception e) {
            throw new PatchException(e.getMessage(), e);
        } finally {
            if (git != null) {
                try {
                    git.branchDelete()
                            .setBranchNames(patchBranch)
                            .setForce(true)
                            .call();
                } catch (GitAPIException e) {
                    Activator.log2(LogService.LOG_ERROR, e.getMessage());
                }
                gitPatchRepository.closeRepository(git, false);
            }
        }
    }

    @Override
    public boolean isStandaloneChild() {
        return env == EnvType.STANDALONE_CHILD;
    }

    @Override
    public boolean alignTo(Map<String, String> versions, List<String> urls, File localMavenRepository, Runnable callback) throws PatchException {
        if (aligning.getAndSet(true)) {
            return false;
        }

        try {
            if (!env.isFabric()) {
                try {
                    // we probably survived fabric:create without refreshing patch-management bundle
                    env = envService.determineEnvironmentType();
                    File patchRepositoryLocation = new File(patchesDir, GitPatchRepositoryImpl.MAIN_GIT_REPO_LOCATION);

                    getGitPatchRepository().close();
                    GitPatchRepositoryImpl repository = new GitPatchRepositoryImpl(env, patchRepositoryLocation,
                            karafHome, karafBase, karafData, patchesDir);
                    setGitPatchRepository(repository);
                    start();

                    // let's tweak the configuration when entering fabric mode
                    // this way we will track other kinds of baselines
                    ensurePatchManagementInitialized();

                    if (master) {
                        // let the caller know that we've configured patch management in "master" container
                        // this is mainly to push the changes from local to cluster git repository
                        // so child/ssh containers created in fabric can fetch correct baselines
                        callback.run();
                    }
                } catch (Exception e) {
                    throw new PatchException(e.getMessage(), e);
                }
            }

            if (env.isFabric()) {
                Git fork = null;
                try {
                    String version = versions.get(env.getProductId());
                    String tagName = String.format(env.getBaselineTagFormat(), version);
                    // we have to be at that tag
                    Git mainRepository = gitPatchRepository.findOrCreateMainGitRepository();
                    fork = gitPatchRepository.cloneRepository(mainRepository, true);
                    gitPatchRepository.checkout(fork)
                            .setName(gitPatchRepository.getMainBranchName())
                            .call();

                    RevTag tag = gitPatchRepository.findCurrentBaseline(fork);
                    if (tag != null && tagName.equals(tag.getTagName())) {
                        if (master) {
                            // in case of `fabric:create --clean`, we have to push references again
                            // from patches/.management/history to data/git/local
                            // and then to data/git/servlet
                            try {
                                mainRepository.push().setPushAll().setPushTags().call();
                                callback.run();
                            } catch (Exception e) {
                                Activator.log(LogService.LOG_WARNING, null, e.getMessage(), e, false);
                            }
                        }
                        return false;
                    }

                    applyUserChanges(fork);
                    ensureCorrectContainerHistory(fork, version);
                    applyChanges(fork, false);

                    if (localMavenRepository != null) {
                        try {
                            File systemRepo = getSystemRepository(karafHome, systemContext);
                            // let's copy artifacts referenced in etc/startup.properties from localMavenRepository to system
                            File etcStartupProperties = new File(karafBase, "etc/startup.properties");
                            try (FileInputStream fis = new FileInputStream(etcStartupProperties)) {
                                Properties props = new Properties();
                                props.load(fis);
                                for (String artifact : props.stringPropertyNames()) {
                                    File target = new File(systemRepo, artifact);
                                    File src = new File(localMavenRepository, artifact);
                                    if (!target.exists() && src.isFile()) {
                                        FileUtils.copyFile(src, target);
                                    }
                                }
                            }
                            // now the URLs from the passed lis
                            for (String url : urls) {
                                String path = Utils.mvnurlToPath(url);
                                if (path != null) {
                                    File target = new File(systemRepo, path);
                                    File src = new File(localMavenRepository, path);
                                    if (!target.exists() && src.isFile()) {
                                        FileUtils.copyFile(src, target);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Activator.log(LogService.LOG_ERROR, null, e.getMessage(), e, false);
                        }
                    }
                    return true;
                } catch (Exception e) {
                    throw new PatchException(e.getMessage(), e);
                } finally {
                    if (fork != null) {
                        gitPatchRepository.closeRepository(fork, true);
                    }
                }
            }

            return false;
        } finally {
            aligning.set(false);
        }
    }

    /**
     * If <code>backupDir</code> exists, restore bundle data from this location and place in Felix bundle cache
     * @param dataCache data cache location (by default: <code>${karaf.home}/data/cache</code>)
     * @param bundle
     * @param backupDir
     */
    private void restoreDataDirectory(String dataCache, Bundle bundle, File backupDir) {
        if (backupDir.isDirectory()) {
            Activator.log2(LogService.LOG_INFO, String.format("Restoring data directory for bundle %s", bundle.toString()));
            File bundleDataDir = new File(dataCache, "bundle" + bundle.getBundleId() + "/data");
            try {
                FileUtils.copyDirectory(backupDir, bundleDataDir);
            } catch (IOException e) {
                Activator.log(LogService.LOG_ERROR, null, e.getMessage(), e, true);
            }
        }
    }

    /**
     * Checks if current installation has old patch data available. If true, then move this data
     * under new patch management directory
     */
    private void migrateOldPatchData() throws IOException {
        // bundle data of Felix
        File systemBundleData = systemContext.getDataFile("patches");
        if (systemBundleData.exists() && systemBundleData.isDirectory()) {
            // move old data from ${karaf.home}/data/cache/bundle0/patches/
            movePatchData(systemBundleData);
        }
        String oldPatchLocation = systemContext.getProperty("fabric8.patch.location");
        if (oldPatchLocation != null && !"".equals(oldPatchLocation)) {
            // move old data from ${karaf.home}/patches/
            //movePatchData(new File(oldPatchLocation));
        }
        String newPatchLocation = systemContext.getProperty("fuse.patch.location");
        if (newPatchLocation != null && !"".equals(newPatchLocation)) {
            //movePatchData(new File(newPatchLocation));
        }
    }

    /**
     * Moves patch files, descriptors and results into managed location
     * @param systemBundleData
     */
    private void movePatchData(File systemBundleData) throws IOException {
        FileUtils.copyDirectory(systemBundleData, patchesDir);
        FileUtils.deleteDirectory(systemBundleData);
    }

}
