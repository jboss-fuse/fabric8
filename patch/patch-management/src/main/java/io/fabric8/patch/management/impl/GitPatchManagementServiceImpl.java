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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric8.patch.management.Activator;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;
import org.osgi.framework.startlevel.BundleStartLevel;

/**
 * <p>An implementation of Git-based patch management system</p>
 * <p>This class maintains single <em>bare</em> git repository (by default in ${karaf.base}/patches/.management/history)
 * and performs git operations in temporary clone+working copies.</p>
 */
public class GitPatchManagementServiceImpl implements GitPatchManagementService {

    private static final String MAIN_GIT_REPO_LOCATION = ".management/history";
    private static final String[] MANAGED_DIRECTORIES = new String[] { "bin", "etc", "lib", "fabric", "licenses", "metatype" };

    private static final Pattern VERSION = Pattern.compile("patch-management-(\\d+\\.\\d+\\.\\d+\\.redhat-[\\d]+)\\.jar");

    /** A pattern of commit message when adding baseling distro */
    private static final String MARKER_BASELINE_COMMIT_PATTERN = "[PATCH/baseline] jboss-fuse-full-%s-baseline";
    /** A pattern of commit message when installing patch-management (this) bundle in etc/startup.properties */
    private static final String MARKER_PATCH_MANAGEMENT_INSTALLATION_COMMIT_PATTERN = "[PATCH/management] patch-management-%s.jar installed in etc/startup.properties";
    /** Commit message when applying user changes to managed directories */
    private static final String MARKER_USER_CHANGES_COMMIT = "[PATCH] Apply user changes";

    private static final DateFormat TS = new SimpleDateFormat("yyyyMMdd-HHmmssSSS");

    private final BundleContext bundleContext;
    private final BundleContext systemContext;

    private File karafHome;
    private String patchLocation;
    // main patches directory at ${fuse.patch.location} (defaults to ${karaf.home}/patches
    private File patchesDir;

    // directory containing "reference", bare git repository to store patch management history
    private File gitPatchManagement;
    // main git (bare) repository at ${fuse.patch.location}/.management/history
    private Git mainRepository;

    // directory to store temporary forks and working copies to perform operations before pushing to
    // "reference" repository
    private File tmpPatchManagement;

    public GitPatchManagementServiceImpl(BundleContext context) {
        this.bundleContext = context;
        this.systemContext = context.getBundle(0).getBundleContext();
        karafHome = new File(System.getProperty("karaf.home"));
        patchLocation = systemContext.getProperty("fuse.patch.location");
        if (patchLocation != null) {
            patchesDir = new File(patchLocation);
        }
    }

    @Override
    public boolean isEnabled() {
        return patchesDir != null && patchesDir.isDirectory() && patchesDir.exists();
    }

    @Override
    public void start() {
        gitPatchManagement = new File(patchesDir, MAIN_GIT_REPO_LOCATION);
        if (!gitPatchManagement.exists()) {
            gitPatchManagement.mkdirs();
        }

        tmpPatchManagement = new File(patchesDir, "tmp");
        if (!tmpPatchManagement.exists()) {
            tmpPatchManagement.mkdirs();
        }
    }

    @Override
    public void stop() {
        if (mainRepository != null) {
            mainRepository.close();
        }
        RepositoryCache.clear();
    }

    /**
     * Check if Fuse/Fabric8 installation is correctly managed by patch mechanism. Check if main git repository
     * is created and is intialized with correct content, there are no conflicts and no pending updates in main Karaf directory.
     * After this method is invoked, we're basically ready to perform rollup patches backed up by git repository
     */
    @Override
    public void ensurePatchManagementInitialized() {
        System.out.println("[PATCH] INITIALIZING PATCH MANAGEMENT SYSTEM");
        Git fork = null;
        try {
            mainRepository = findOrCreateGitRepository(gitPatchManagement, true);
            // prepare single fork for all the below operations
            fork = cloneRepository(mainRepository, true);
            InitializationType state = checkMainRepositoryState(fork);
            switch (state) {
                case INSTALL_BASELINE:
                    // track initial configuration
                    trackBaselineRepository(fork);
                    // fall down the next case, don't break!
                case INSTALL_PATCH_MANAGEMENT_BUNDLE:
                    // add possible user changes since the distro was first run
                    applyUserChanges(fork);
                    // install patch management bundle in etc/startup.properties to overwrite possible user change to that file
                    installPatchManagementBundle(fork);
                    break;
                case ADD_USER_CHANGES:
                    // because patch management is already installed, we have to add consecutive (post patch-management installation) changes
                    applyUserChanges(fork);
                    break;
                case READY:
                    break;
            }
        } catch (GitAPIException | IOException e) {
            System.err.println("[PATCH-error] " + e.getMessage());
            e.printStackTrace();
            // PANIC
        } finally {
            if (fork != null) {
                closeRepository(fork, true);
            }
        }
    }

    /**
     * Returns at least initialized git repository at specific location. When the repository doesn't exist, it is
     * created with single branch <code>master</code> and one, empty, initial commit.
     * @param directory
     * @param bare
     * @return
     */
    protected Git findOrCreateGitRepository(File directory, boolean bare) throws IOException {
        try {
            System.out.println("[PATCH] OPENING GIT");
            return Git.open(directory);
        } catch (RepositoryNotFoundException fallback) {
            try {
                System.out.println("[PATCH] INITIALIZING GIT");
                Git git = Git.init()
                        .setBare(bare)
                        .setDirectory(directory)
                        .call();
                Git fork = cloneRepository(git, false);
                commit(fork, "[PATCH] initialization").call();
                push(fork);
                closeRepository(fork, true);
                return git;
            } catch (GitAPIException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    /**
     * Retrieves {@link Git} handle to temporary fork of another repository. The returned repository is connected
     * to the forked repo using "origin" remote.
     * @param git
     * @param fetchAndCheckout
     * @return
     */
    protected Git cloneRepository(Git git, boolean fetchAndCheckout) throws GitAPIException, IOException {
        File tmpLocation = new File(tmpPatchManagement, TS.format(new Date()));
        Git fork = Git.init().setBare(false).setDirectory(tmpLocation).call();
        StoredConfig config = fork.getRepository().getConfig();
        config.setString("remote", "origin", "url", git.getRepository().getDirectory().getCanonicalPath());
        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        config.save();

        if (fetchAndCheckout) {
            fork.fetch().setRemote("origin").call();
            fork.checkout()
                    .setCreateBranch(true)
                    .setName("master")
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .setStartPoint("origin/master")
                    .call();
        }

        return fork;
    }

    /**
     * Closes {@link Git} and if <code>deleteWorkingCopy</code> is <code>true</code>, removes repository and, working copy.
     * @param fork
     * @param deleteWorkingCopy
     */
    protected void closeRepository(Git fork, boolean deleteWorkingCopy) {
        fork.close();
        if (deleteWorkingCopy) {
            FileUtils.deleteQuietly(fork.getRepository().getDirectory().getParentFile());
        }
    }

    /**
     * <p>Checks the state of git repository that track the patch history</p>
     * @param git a clone + working copy connected to main repository
     * @return the state the repository is at
     */
    private InitializationType checkMainRepositoryState(Git git) throws GitAPIException, IOException {
        // we need baseline distribution of Fuse/AMQ at current version
        String currentFuseVersion = determineVersion("fuse");
        String currentFabricVersion = bundleContext.getBundle().getVersion().toString();

        if (!containsCommit(git, "master", String.format(MARKER_BASELINE_COMMIT_PATTERN, currentFuseVersion))) {
            // we have empty repository
            return InitializationType.INSTALL_BASELINE;
        } else if (!containsCommit(git, "master", String.format(MARKER_PATCH_MANAGEMENT_INSTALLATION_COMMIT_PATTERN, currentFabricVersion))) {
            // we already tracked the basline repo, but it seems we're running from patch-management bundle that was simply dropped into deploy/
            return InitializationType.INSTALL_PATCH_MANAGEMENT_BUNDLE;
        } else {
            System.out.println("[PATCH] Baseline distribution already committed for version " + currentFuseVersion);
            System.out.println("[PATCH] patch-management bundle is already installed in etc/startup.properties at version " + currentFabricVersion);
            // we don't check if there are any user changes now, but we will be doing it anyway at each startup of this bundle
            return InitializationType.ADD_USER_CHANGES;
        }
    }

    /**
     * Checks whether a branch in repository contains named commit
     * @param fork
     * @param master
     * @param commitMessage
     * @return
     */
    private boolean containsCommit(Git fork, String master, String commitMessage) throws IOException, GitAPIException {
        ObjectId masterHEAD = fork.getRepository().resolve("master");
        Iterable<RevCommit> log = fork.log().add(masterHEAD).call();
        for (RevCommit rc : log) {
            if (rc.getFullMessage().equals(commitMessage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unpacks a ZIP file and adds (recursively) its content to git repository
     * @param git
     * @param baselineDistribution a ZIP file to unpack and add to the repository
     */
    private void unpackToRepository(Git git, File baselineDistribution) throws IOException {
        File wc = git.getRepository().getDirectory().getParentFile();
        ZipFile zf = new ZipFile(baselineDistribution);
        try {
            for (Enumeration<ZipArchiveEntry> e = zf.getEntries(); e.hasMoreElements(); ) {
                ZipArchiveEntry entry = e.nextElement();
                String name = entry.getName();
                name = name.substring(name.indexOf('/'));
                if (entry.isDirectory()) {
                    new File(wc, name).mkdirs();
                } else if (!entry.isUnixSymlink()) {
                    File file = new File(wc, name);
                    file.getParentFile().mkdirs();
                    FileOutputStream output = new FileOutputStream(file);
                    IOUtils.copyLarge(zf.getInputStream(entry), output);
                    IOUtils.closeQuietly(output);
                    Files.setPosixFilePermissions(file.toPath(), getPermissionsFromUnixMode(file, entry.getUnixMode()));
                }
            }
        } finally {
            if (zf != null) {
                zf.close();
            }
        }
    }

    /**
     * Converts numeric UNIX permissions to a set of {@link PosixFilePermission}
     * @param file
     * @param unixMode
     * @return
     */
    private Set<PosixFilePermission> getPermissionsFromUnixMode(File file, int unixMode) {
        String numeric = Integer.toOctalString(unixMode);
        if (numeric != null && numeric.length() > 3) {
            numeric = numeric.substring(numeric.length() - 3);
        }
        if (numeric == null) {
            return PosixFilePermissions.fromString(file.isDirectory() ? "rwxrwxr-x" : "rw-rw-r--");
        }

        Set<PosixFilePermission> result = new HashSet<>();
        int shortMode = Integer.parseInt(numeric, 8);
        if ((shortMode & 0400) == 0400)
            result.add(PosixFilePermission.OWNER_READ);
        if ((shortMode & 0200) == 0200)
            result.add(PosixFilePermission.OWNER_WRITE);
        if ((shortMode & 0100) == 0100)
            result.add(PosixFilePermission.OWNER_EXECUTE);
        if ((shortMode & 0040) == 0040)
            result.add(PosixFilePermission.GROUP_READ);
        if ((shortMode & 0020) == 0020)
            result.add(PosixFilePermission.GROUP_WRITE);
        if ((shortMode & 0010) == 0010)
            result.add(PosixFilePermission.GROUP_EXECUTE);
        if ((shortMode & 0004) == 0004)
            result.add(PosixFilePermission.OTHERS_READ);
        if ((shortMode & 0002) == 0002)
            result.add(PosixFilePermission.OTHERS_WRITE);
        if ((shortMode & 0001) == 0001)
            result.add(PosixFilePermission.OTHERS_EXECUTE);

        return result;
    }

    /**
     * Return version of product (Fuse, Fabric8) used.
     * @param product
     * @return
     */
    private String determineVersion(String product) {
        File versions = new File(karafHome, "fabric/import/fabric/profiles/default.profile/io.fabric8.version.properties");
        if (versions.exists() && versions.isFile()) {
            Properties props = new Properties();
            try {
                props.load(new FileInputStream(versions));
                return props.getProperty(product);
            } catch (IOException e) {
                System.err.println("[PATCH-error] " + e.getMessage());
                return null;
            }
        } else {
            System.err.println("[PATCH-error] Can't find io.fabric8.version.properties file in default profile");
        }
        return null;
    }

    /**
     * Adds baseline distribution to the repository
     * @param git non-bare repository to perform the operation
     */
    private void trackBaselineRepository(Git git) throws IOException, GitAPIException {
        // initialize repo with baseline version and push to reference repo
        String currentFuseVersion = determineVersion("fuse");
        String currentFabricVersion = determineVersion("fabric");

        File baselineDistribution = new File(patchesDir, String.format("jboss-fuse-full-%s-baseline.zip", currentFuseVersion));
        if (!(baselineDistribution.exists() && baselineDistribution.isFile())) {
            File repositoryDir = new File(karafHome.getCanonicalPath() + String.format("/system/org/jboss/fuse/jboss-fuse-full/%s", currentFuseVersion));
            baselineDistribution = new File(repositoryDir, String.format("jboss-fuse-full-%s-baseline.zip", currentFuseVersion));
        }
        if (baselineDistribution.exists() && baselineDistribution.isFile()) {
            unpackToRepository(git, baselineDistribution);
            git.add()
                    .addFilepattern(".")
                    .call();
            commit(git, String.format(MARKER_BASELINE_COMMIT_PATTERN, currentFuseVersion)).call();
            push(git);
        } else {
            String message = "Can't find baseline distribution \"" + baselineDistribution.getName() + "\" in patches dir or inside system repository.";
            System.err.println("[PATCH-error] " + message);
            throw new IOException(message);
        }
    }

    /**
     * Applies existing user changes in ${karaf.home}/{bin,etc,fabric,lib,licenses,metatype} directories to patch
     * management Git repository, doesn't modify ${karaf.home}
     * @param git non-bare repository to perform the operation
     */
    private void applyUserChanges(Git git) throws GitAPIException, IOException {
        File wcDir = git.getRepository().getDirectory().getParentFile();
        File karafBase = karafHome;

        try {
            // let's simply copy all user files on top of git working copy
            // then we can check the differences simply by committing the changes
            // there should be no conflicts, because we're not merging anything
            for (String dir : MANAGED_DIRECTORIES) {
                File destDir = new File(wcDir, dir);
                // delete content of wc's copy of the dir to handle user removal of files
                FileUtils.deleteQuietly(destDir);
                FileUtils.copyDirectory(new File(karafBase, dir), destDir);
                if ("bin".equals(dir)) {
                    // repair file permissions
                    for (File script : destDir.listFiles()) {
                        if (!script.getName().endsWith(".bat")) {
                            Files.setPosixFilePermissions(script.toPath(), getPermissionsFromUnixMode(script, 0775));
                        }
                    }
                }
            }

            // commit the changes to main repository
            Status status = git.status().call();
            if (!status.isClean()) {
                System.out.println("[PATCH] Storing user changes");
                git.add()
                        .addFilepattern(".")
                        .call();
                for (String name : status.getMissing()) {
                    git.rm()
                            .addFilepattern(name)
                            .call();
                }
                commit(git, MARKER_USER_CHANGES_COMMIT).call();
                push(git);

                // now main repository has exactly the same content as ${karaf.home}
                // TODO we have two methods of synchronization wrt future rollup changes:
                // 1. we can pull from "origin" in the MAIN working copy (${karaf.hoome}) (making sure the copy is initialized)
                // 2. we can apply rollup patch to temporary fork + working copy, perform merges, resolve conflicts, etc
                //    and if everything goes fine, simply override ${karaf.hoome} content with the working copy content
                // method 2. doesn't require real working copy and ${karaf.home}/.git directory
            } else {
                System.out.println("[PATCH] No user changes");
            }
        } catch (GitAPIException | IOException e) {
            System.err.println("[PATCH-error] " + e.getMessage());
        }
    }

    /**
     * Assuming there's no track of adding patch-management bundle to etc/startup.properties, we also have to
     * actually <em>install</em> this bundle in ${karaf.home}/system
     * @param git non-bare repository to perform the operation
     */
    private void installPatchManagementBundle(Git git) throws IOException, GitAPIException {
        // if user simply osgi:install the patch-management bundle then adding the mvn: URI to etc/startup.properties is enough
        // but it patch-management was dropped into deploy/, we have to copy it to ${karaf.default.repository}
        File fileinstallDeployDir = getDeployDir();
        String bundleVersion = bundleContext.getBundle().getVersion().toString();
        String patchManagementArtifact = String.format("patch-management-%s.jar", bundleVersion);
        File deployedPatchManagement = new File(fileinstallDeployDir, patchManagementArtifact);
        if (deployedPatchManagement.exists() && deployedPatchManagement.isFile()) {
            // let's copy it to system/
            File systemRepo = new File(karafHome, System.getProperty("karaf.default.repository", "system"));
            String targetFile = String.format("io/fabric8/patch/patch-management/%s/patch-managemen-%s.jar", bundleVersion, bundleVersion);
            File target = new File(systemRepo, targetFile);
            target.getParentFile().mkdirs();
            FileUtils.copyFile(deployedPatchManagement, target);
            // don't delete source artifact, because fileinstall can decide to uninstall the bundle!
            // we will do it in cleanupDeployDir()
        }

        // let's modify etc/startup.properties (first in Git!)
        StringBuilder sb = new StringBuilder();
        String lf = System.lineSeparator();
        sb.append(lf);
        sb.append("# installed by patch-management-").append(bundleVersion).append(lf);
        sb.append(String.format("io/fabric8/patch/patch-management/%s/patch-managemen-%s.jar=%d", bundleVersion, bundleVersion, Activator.PATCH_MANAGEMENT_START_LEVEL)).
                append(lf);
        FileUtils.write(new File(git.getRepository().getDirectory().getParent(), "etc/startup.properties"), sb.toString(), true);

        git.add()
                .addFilepattern("etc/startup.properties")
                .call();
        RevCommit commit = commit(git, String.format(MARKER_PATCH_MANAGEMENT_INSTALLATION_COMMIT_PATTERN, bundleVersion)).call();
        push(git);

        // "checkout" the above change in main "working copy" (${karaf.home})
        applyChanges(git, commit);

        bundleContext.getBundle().adapt(BundleStartLevel.class).setStartLevel(Activator.PATCH_MANAGEMENT_START_LEVEL);

        System.out.println(String.format("[PATCH] patch-management-%s.jar installed in etc/startup.properties. Please restart.", bundleVersion));
    }

    /**
     * <p>This method takes a list of commits and performs manual update to ${karaf.home}. If ${karaf.home} was also a
     * checked out working copy, it'd be a matter of <code>git pull</code>. We may consider this implementation, but now
     * I don't want to keep <code>.git</code> directory in ${karaf.home}. Also, jgit doesn't support <code>.git</code>
     * <em>platform agnostic symbolic link</em> (see: <code>git init --separate-git-dir</code>)</p>
     * <p>We don't have to fetch data from repository blobs, because <code>git</code> still points to checked-out
     * working copy</p>
     * @param git
     * @param commits
     */
    private void applyChanges(Git git, RevCommit ... commits) throws IOException, GitAPIException {
        File wcDir = git.getRepository().getWorkTree();

        ObjectReader reader = git.getRepository().newObjectReader();
        for (RevCommit commit : commits) {
            // assume we don't operate on merge commits, at least now
            // it'll be needed when we start patch installation
            RevCommit parent = commit.getParent(0);
            CanonicalTreeParser ctp1 = new CanonicalTreeParser();
            ctp1.reset(reader, new RevWalk(git.getRepository()).parseCommit(parent).getTree());
            CanonicalTreeParser ctp2 = new CanonicalTreeParser();
            ctp2.reset(reader, commit.getTree());

            List<DiffEntry> diff = git.diff()
                    .setShowNameAndStatusOnly(true)
                    .setOldTree(ctp1)
                    .setNewTree(ctp2)
                    .call();
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
                        System.out.println("[PATCH-change] Modyfying " + newPath);
                        FileUtils.copyFile(new File(wcDir, newPath), new File(karafHome, newPath));
                        break;
                    case DELETE:
                        System.out.println("[PATCH-change] Deleting " + oldPath);
                        FileUtils.deleteQuietly(new File(karafHome, oldPath));
                        break;
                    case COPY:
                    case RENAME:
                        // not handled now
                        break;
                }
            }
        }
    }

    /**
     * Retrieves location of fileinstall-managed "deploy" directory, where bundles can be dropped
     * @return
     */
    private File getDeployDir() throws IOException {
        String deployDir = null;
        File fileinstallCfg = new File(System.getProperty("karaf.etc"), "org.apache.felix.fileinstall-deploy.cfg");
        if (fileinstallCfg.exists() && fileinstallCfg.isFile()) {
            Properties props = new Properties();
            FileInputStream stream = new FileInputStream(fileinstallCfg);
            props.load(stream);
            deployDir = props.getProperty("felix.fileinstall.dir");
            // TODO should do full substitution instead of these two (org.apache.felix.utils.properties.Properties)
            if (deployDir.contains("${karaf.home}")) {
                deployDir = deployDir.replace("${karaf.home}", System.getProperty("karaf.home"));
            } else if (deployDir.contains("${karaf.base}")) {
                deployDir = deployDir.replace("${karaf.base}", System.getProperty("karaf.base"));
            }
            IOUtils.closeQuietly(stream);
        } else {
            deployDir = karafHome.getAbsolutePath() + "/deploy";
        }
        return new File(deployDir);
    }

    @Override
    public void cleanupDeployDir() throws IOException {
        Version ourVersion = bundleContext.getBundle().getVersion();
        File deploy = getDeployDir();
        File[] deployedPatchManagementBundles = deploy.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return VERSION.matcher(name).matches();
            }
        });
        for (File anotherPatchManagementBundle : deployedPatchManagementBundles) {
            Matcher matcher = VERSION.matcher(anotherPatchManagementBundle.getName());
            matcher.find();
            String version = matcher.group(1);
            Version deployedVersion = new Version(version);
            if (ourVersion.compareTo(deployedVersion) >= 0) {
                System.out.println("[PATCH] Deleting " + anotherPatchManagementBundle);
                FileUtils.deleteQuietly(anotherPatchManagementBundle);
            }
        }
    }

    /**
     * Returns {@link CommitCommand} with Author and Message set
     * @param git
     * @return
     */
    private CommitCommand commit(Git git, String message) {
        return git.commit()
                .setAuthor(karafHome.getName(), "fuse@redhat.com")
                .setMessage(message);
    }

    /**
     * Shorthand for <code>git push origin master</code>
     * @param git
     */
    private void push(Git git) throws GitAPIException {
        git.push()
                .setRemote("origin")
                .setRefSpecs(new RefSpec("master"))
                .call();
    }

    /**
     * State of <code>${fuse.patch.location}/.management/history</code> repository indicating next action required
     */
    private enum InitializationType {
        /** Everything is setup */
        READY,
        /** Baseline is committed into the repository and patch-management bundle is installed in etc/startup.properties */
        ADD_USER_CHANGES,
        /** Baseline is committed into the repository, patch-management bundle is still only in deploy/ directory and not in etc/startup.properties */
        INSTALL_PATCH_MANAGEMENT_BUNDLE,
        /** Baseline isn't installed yet (or isn't installed at desired version) */
        INSTALL_BASELINE
    }

}
