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
package io.fabric8.groups.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.fabric8.groups.Group;
import io.fabric8.groups.GroupListener;
import io.fabric8.groups.NodeState;
import io.fabric8.utils.NamedThreadFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.listen.ListenerContainer;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.utils.EnsurePath;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A utility that attempts to keep all data from all children of a ZK path locally cached. This class
 * will watch the ZK path, respond to update/create/delete events, pull down the data, etc. You can
 * register a listener that will get notified when changes occur.</p>
 * <p>There are two <em>modes</em> of operation when using {@link ZooKeeperGroup}:<ul>
 *     <li>"cluster member registration" when {@link #update(NodeState)} is called</li>
 *     <li>"cluster listener" without using {@link #update(NodeState)} to listen to cluster events and detect changed
 *     master node.</li>
 * </ul></p>
 * <p><b>IMPORTANT</b> - it's not possible to stay transactionally in sync. Users of this class must
 * be prepared for false-positives and false-negatives. Additionally, always use the version number
 * when updating data to avoid overwriting another process' change.</p>
 */
public class ZooKeeperGroup<T extends NodeState> implements Group<T> {

    public ObjectMapper MAPPER = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static private final Logger LOG = LoggerFactory.getLogger("io.fabric8.cluster");

    private final Class<T> clazz;
    private final CuratorFramework client;
    private final String path;
    private final ExecutorService executorService;
    private final EnsurePath ensurePath;
    private final BlockingQueue<Operation> operations = new LinkedBlockingQueue<Operation>();
    private final ListenerContainer<GroupListener<T>> listeners = new ListenerContainer<GroupListener<T>>();

    // mapping from path id into ChildData - all cluster member registrations are kept here - even those not ready
    protected final ConcurrentMap<String, ChildData<T>> currentData = Maps.newConcurrentMap();

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    protected final SequenceComparator sequenceComparator = new SequenceComparator();
    private final String uuid = UUID.randomUUID().toString();

    // "id" is actual sequential, ephemeral node created for this group - only if given group
    // is used in "register cluster member" mode, not in "listen to master changes" mode
    private volatile String id;
    // to help detecting whether ZK Group update failed
    private final AtomicBoolean creating = new AtomicBoolean();
    // flag indicating that ephemeral node could be created in registry, but exact sequence ID is uknown
    // this status means we may have (temporary - for the period of ZK session) duplication of nodes
    private final AtomicBoolean unstable = new AtomicBoolean();
    private volatile T state;

    String source = "?";

    // counter to number ZooKeeperGroups related operations being offered/invoked
    AtomicInteger counter = new AtomicInteger(0);

    private final Watcher childrenWatcher = new Watcher() {
        @Override
        public void process(WatchedEvent event) {
            // NodeCreated and NodeDeleted are for the cluster node itself (e.g., /fabric/registry/clusters/git),
            // so we're only interested in NodeChildrenChanged event
            if (event.getType() == Event.EventType.NodeChildrenChanged) {
                // only interested in real change events, eg no refresh on Keeper.Disconnect
                if (LOG.isDebugEnabled()) {
                    LOG.debug(ZooKeeperGroup.this + ", childrenWatcher: offering refresh after detecting event=" + event);
                }
                // STANDARD will get data when detecting new node among children nodes, it won't
                // cause data reading for existing, but updated nodes, but for such case, dataWatched is
                // the place to fetch updated data
                offerOperation(new RefreshOperation(ZooKeeperGroup.this, RefreshMode.STANDARD));
            }
        }
    };

    private final Watcher dataWatcher = new Watcher() {
        @Override
        public void process(WatchedEvent event) {
            try {
                // node removal (org.apache.zookeeper.server.upgrade.DataTreeV1.deleteNode()) contains:
                // - dataWatches.triggerWatch(path, EventType.NodeDeleted);
                // - childWatches.triggerWatch(path, EventType.NodeDeleted, processed);
                // - childWatches.triggerWatch(parentName.equals("")?"/":parentName, EventType.NodeChildrenChanged);
                // so there's no need to handle NodeDeleted here - this will be handled by children watcher
//                if (event.getType() == Event.EventType.NodeDeleted) {
//                    if (LOG.isDebugEnabled()) {
//                        LOG.debug(ZooKeeperGroup.this + ", dataWatcher: remove(" + event.getPath() + ") after event=" + event);
//                    }
//                    if (remove(event.getPath())) {
//                        offerOperation(new EventOperation(ZooKeeperGroup.this, GroupListener.GroupEvent.CHANGED));
//                    }
//                }
                // node update (org.apache.zookeeper.server.upgrade.DataTreeV1.setData()) contains:
                // - dataWatches.triggerWatch(path, EventType.NodeDataChanged);
                // so it's a good place to handle NodeDataChanged here
                if (event.getType() == Event.EventType.NodeDataChanged) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(ZooKeeperGroup.this + ", dataWatcher: offering getData(" + event.getPath() + ") after detecting event=" + event);
                    }
                    offerOperation(new GetDataOperation(ZooKeeperGroup.this, event.getPath(), true));
                }
            } catch (Exception e) {
                handleException(e);
            }
        }
    };

    private final ConnectionStateListener connectionStateListener = new ConnectionStateListener() {
        @Override
        public void stateChanged(CuratorFramework client, ConnectionState newState) {
            handleStateChange(newState);
        }
    };

    /**
     * @param client the client
     * @param path   path to watch
     */
    public ZooKeeperGroup(CuratorFramework client, String path, Class<T> clazz) {
        this(client, path, clazz,
                Executors.newSingleThreadExecutor(new NamedThreadFactory("ZKGroup")));
    }

    /**
     * @param client        the client
     * @param path          path to watch
     * @param threadFactory factory to use when creating internal threads
     */
    public ZooKeeperGroup(CuratorFramework client, String path, Class<T> clazz, ThreadFactory threadFactory) {
        this(client, path, clazz, Executors.newSingleThreadExecutor(threadFactory));
    }

    /**
     * @param client          the client
     * @param path            path to watch
     * @param executorService ExecutorService to use for the ZooKeeperGroup's background thread
     */
    public ZooKeeperGroup(CuratorFramework client, String path, Class<T> clazz, final ExecutorService executorService) {
        this(null, client, path, clazz, executorService);
    }

    /**
     * @param source
     * @param client the client
     * @param path   path to watch
     */
    public ZooKeeperGroup(String source, CuratorFramework client, String path, Class<T> clazz) {
        this(source, client, path, clazz,
                Executors.newSingleThreadExecutor(new NamedThreadFactory("ZKGroup")));
    }

    /**
     * @param source
     * @param client        the client
     * @param path          path to watch
     * @param threadFactory factory to use when creating internal threads
     */
    public ZooKeeperGroup(String source, CuratorFramework client, String path, Class<T> clazz, ThreadFactory threadFactory) {
        this(source, client, path, clazz, Executors.newSingleThreadExecutor(threadFactory));
    }

    /**
     * @param source
     * @param client          the client
     * @param path            path to watch
     * @param executorService ExecutorService to use for the ZooKeeperGroup's background thread
     */
    public ZooKeeperGroup(String source, CuratorFramework client, String path, Class<T> clazz, final ExecutorService executorService) {
        this.client = client;
        this.path = path;
        this.clazz = clazz;
        this.source = source == null ? "?" : source;
        LOG.info("Creating " + this);
        this.executorService = executorService;
        ensurePath = client.newNamespaceAwareEnsurePath(path);
    }

    /**
     * Start the cache. The cache is not started automatically. You must call this method.
     */
    public void start() {
        LOG.info("Starting " + this);
        if (started.compareAndSet(false, true)) {
            connected.set(client.getZookeeperClient().isConnected());

            if (isConnected()) {
                handleStateChange(ConnectionState.CONNECTED);
            }

            client.getConnectionStateListenable().addListener(connectionStateListener);
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    mainLoop();
                }
            });
        }
    }

    /**
     * Close/end the cache
     *
     * @throws IOException errors
     */
    @Override
    public void close() throws IOException {
        LOG.info("Closing " + this);
        if (started.compareAndSet(true, false)) {
            client.getConnectionStateListenable().removeListener(connectionStateListener);
            executorService.shutdownNow();
            try {
                executorService.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw (IOException) new InterruptedIOException().initCause(e);
            }
            try {
                doUpdate(null);
                if (isConnected()) {
                    callListeners(GroupListener.GroupEvent.DISCONNECTED);
                }
            } catch (Exception e) {
                handleException(e);
            }
            listeners.clear();
            MAPPER.getTypeFactory().clearCache();
            MAPPER = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            client.clearWatcherReferences(childrenWatcher);
            client.clearWatcherReferences(dataWatcher);
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void add(GroupListener<T> listener) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(this + ".add-listener(" + listener.getClass().getName() + ")");
        }
        listeners.addListener(listener);
    }

    @Override
    public void remove(GroupListener<T> listener) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(this + ".remove-listener(" + listener.getClass().getName() + ")");
        }
        listeners.removeListener(listener);
    }

    /**
     * <p>Calling this method on ZooKeeperGroup changes the <em>mode</em> of the group from
     * <em>read only</em> to <em>cluster member registration</em>.
     * With this method we can register a <em>member</em> that may become master at some point.</p>
     *
     * <p>When the state is different than previously configured, data is written to ZooKeeper
     * registry.</p>
     *
     * @param state the new state of this group member
     */
    @Override
    public void update(T state) {
        T oldState = this.state;
        this.state = state;

        if (id != null) {
            // if we have id we don't want to handle create + set-ready state separately, so the state
            // is just ready
            // but if the underlying (server-side) path is gone (see fabric:git-master command)
            // we'll have to unset this "ready" flag
            state.setReady();
        }

        if (started.get()) {
            boolean update = state == null && oldState != null
                        ||   state != null && oldState == null
                        || !Arrays.equals(encode(state), encode(oldState));
            if (update) {
                offerOperation(new CompositeOperation(this,
                        new RefreshOperation(this, RefreshMode.FORCE_GET_DATA_AND_STAT),
                        new UpdateOperation<T>(this, state)
                ));
            }
        }
    }

    /**
     * Actual work done when cluster member needs to be registered (or updated, or deleted)
     * in ZooKeeper registry.
     * @param state
     * @throws Exception
     */
    protected void doUpdate(T state) throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug(this + ": doUpdate, state: " + state);
        }
        if (state == null) {
            // unregistration of cluster memeber
            if (id != null) {
                // happy path - we know what to unregister
                try {
                    if (isConnected()) {
                        LOG.info(this + ": deleting node " + id);
                        client.delete().guaranteed().forPath(id);
                        unstable.set(false);
                    } else {
                        LOG.warn(this + ": can't delete state - client disconnected. Ephemeral, sequence node will be removed on session timeout.");
                    }
                } catch (KeeperException.NoNodeException e) {
                    // Ignore - nothing to unregister
                } finally {
                    id = null;
                }
            } else if (creating.get()) {
                // we don't know what to unregister, but we know that registration started
                // without success (without returning a path)
                unstable.set(true);
                LOG.warn(this + ": ephemeral node could have been created in the registry, but ZooKeeper group didn't record its id.");
            }
        } else if (isConnected()) {
            // (re)registration of cluster member

            // We could have created the sequence, but then have crashed and our entry is already registered.
            // However, we ignore old ephemeral nodes, and create new ones. We can have double nodes for a bit,
            // but the old ones should be deleted by the server when session is invalidated.
            // See: https://issues.jboss.org/browse/FABRIC-1238
            if (id == null) {
                // just create the membership data for the first time (from our ZooKeeperGroup
                // point of view)
                createEphemeralNode(state);
            } else {
                // update the membership data
                try {
                    updateEphemeralNode(state);
                } catch (KeeperException.NoNodeException e) {
                    // but update didn't found the id to update, so let's create instead
                    LOG.info(this + ": node " + id + " wasn't updated, as it's no longer available in ZooKeeper registry. Creating new node instead.");
                    id = null;
                    // we'll be creating new path, so the state should be marked unready if it was ready
                    state.unsetReady();
                    createEphemeralNode(state);
                }
            }
        }
    }

    /**
     * <p>Create a node for this cluster member. This is used used only if this ZooKeeperGroup is used not in <em>read-only</em>
     * mode (notification mode), i.e., it's used only of this ZooKeeperGroup is used to actually add/update child,
     * sequential, ephemeral nodes under group's ZooKeeper path.</p>
     *
     * <p>This method is fragile and non transactional - sequential node may be successfully created in ZooKeeper, but
     * the resulting, generated at server-side, path name/id (like {@code /fabric/registry/clusters/git/00000000125})
     * may not return successfully here. I.e., {@link org.apache.curator.framework.api.PathAndBytesable#forPath(String)}
     * may not manage to return the ID. In such case, such <em>unstable</em> cluster member registration should
     * not be treated as <em>finished</em> and not considered as meaningful information (should be neither a master
     * nor a slave).</p>
     *
     * @param state
     * @return
     * @throws Exception
     */
    private void createEphemeralNode(T state) throws Exception {
        state.uuid = uuid;
        creating.set(true);
        byte[] encoded = encode(state);
        LOG.info(this + ": creating new node for state: " + new String(encoded));
        String pathId = client.create().creatingParentsIfNeeded()
            .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
            .forPath(path + "/0", encoded);
        id = pathId;
        LOG.info(this + ": successfully created new node, ZK path: " + id);

        // we have ID, so we have connection between actual ZKNode and ZooKeeperGroup.id
        // and we can properly update/delete the node later. let's make the cluster membership data "ready" - even
        // if this update may fail - at least we won't end with "dangling", but ready (from the perspective of
        // other listeners) cluster membership data
        try {
            state.setReady();
            updateEphemeralNode(state);
        } catch (Exception e) {
            LOG.warn(this + ": Problem occurred when marking registered cluster member data as ready: " + e.getMessage(), e);
        }

        creating.set(false);
        unstable.set(false);

        // so far, we were cleaning old, unstable, unready nodes for this ZooKeeperGroup here.
        // after ENTESB-11955 we'll be doing it when handling refresh()
//        state.uuid = uuid;
//        prunePartialState(state, id);
//        state.uuid = null;
    }

    /**
     * Update a ZooKeeper node for this cluster member. This method assumes there's known {@link ZooKeeperGroup#id}.
     *
     * @param state
     * @throws Exception
     */
    private void updateEphemeralNode(T state) throws Exception {
        state.uuid = uuid;
        byte[] encoded = encode(state);
        LOG.info(this + ": updating node " + id + " with new state: " + new String(encoded));
        client.setData().forPath(id, encoded);
        state.uuid = null;
    }

    // remove ephemeral sequential nodes created on server but not visible on client
    private void prunePartialState(final T ourState, final String pathId) throws Exception {
        if (ourState.uuid != null) {
            clearAndRefresh(true, true);
            List<ChildData<T>> children = new ArrayList<ChildData<T>>(currentData.values());
            for (ChildData<T> child : children) {
                if (ourState.uuid.equals(child.getNode().uuid) && !child.getPath().equals(pathId)) {
                    LOG.info(this + ": deleting partially created znode: " + child.getPath());
                    client.delete().guaranteed().forPath(child.getPath());
                }
            }
        }
    }

    @Override
    public Map<String, T> members() {
        List<ChildData<T>> children = getActiveOrderedChildren();
        Map<String, T> members = new LinkedHashMap<String, T>();
        for (ChildData<T> child : children) {
            members.put(child.getPath(), child.getNode());
        }
        return members;
    }

    @Override
    public boolean isMaster() {
        List<ChildData<T>> children = getActiveOrderedChildren();
        return (!children.isEmpty() && children.get(0).getPath().equals(id));
    }

    @Override
    public T master() {
        List<ChildData<T>> children = getActiveOrderedChildren();
        if (children.isEmpty()) {
            return null;
        }
        return children.get(0).getNode();
    }

    @Override
    public List<T> slaves() {
        List<ChildData<T>> children = getActiveOrderedChildren();
        List<T> slaves = new ArrayList<T>();
        for (int i = 1; i < children.size(); i++) {
            slaves.add(children.get(i).getNode());
        }
        return slaves;
    }

    /**
     * Filter stale nodes and return only active children from the current data sorted from oldest (lowest
     * sequence path ID) to newest (highest sequence path ID).
     *
     * @return list of active children and data
     */
    protected List<ChildData<T>> getActiveOrderedChildren() {
        Map<String, ChildData<T>> filtered = new HashMap<>();
        for (ChildData<T> child : currentData.values()) {
            T node = child.getNode();
            if (node != null
                    && node.isReady()
                    && (!filtered.containsKey(node.getContainer())
                    || filtered.get(node.getContainer()).getPath().compareTo(child.getPath()) < 0)) {
                // use child's cluster data either if there wasn't any data for given container
                // or if child cluster data is "newer" (in terms of ZK sequence node id)
                filtered.put(node.getContainer(), child);
            }
        }
        ArrayList<ChildData<T>> result = new ArrayList<>(filtered.values());
        Collections.sort(result, sequenceComparator);
        return result;
    }

    @Override
    public T getLastState() {
        return this.state;
    }

    /**
     * Return the cache listenable
     *
     * @return listenable
     */
    public ListenerContainer<GroupListener<T>> getListenable() {
        return listeners;
    }

    /**
     * Return the current data. There are no guarantees of accuracy. This is
     * merely the most recent view of the data. The data is returned in sorted order.
     *
     * @return list of children and data
     */
    public List<ChildData> getCurrentData() {
        return ImmutableList.copyOf(Sets.<ChildData>newTreeSet(currentData.values()));
    }

    /**
     * Return the current data for the given path. There are no guarantees of accuracy. This is
     * merely the most recent view of the data. If there is no child with that path, <code>null</code>
     * is returned.
     *
     * @param fullPath full path to the node to check
     * @return data or null
     */
    public ChildData getCurrentData(String fullPath) {
        return currentData.get(fullPath);
    }

    /**
     * Clear out current data and begin a new query on the path
     *
     * @throws Exception errors
     */
    public void clearAndRefresh() throws Exception {
        clearAndRefresh(false, false);
    }

    /**
     * Clear out current data and begin a new query on the path
     *
     * @param force - whether to force clear and refresh to trigger updates
     * @param sync  - whether to run this synchronously (block current thread) or asynchronously
     * @throws Exception errors
     */
    public void clearAndRefresh(boolean force, boolean sync) throws Exception {
        RefreshMode mode = force ? RefreshMode.FORCE_GET_DATA_AND_STAT : RefreshMode.STANDARD;
        currentData.clear();
        if (sync) {
            this.refresh(mode);
        } else {
            offerOperation(new RefreshOperation(this, mode));
        }
    }

    /**
     * Clears the current data without beginning a new query and without generating any events
     * for listeners.
     */
    public void clear() {
        currentData.clear();
    }

    enum RefreshMode {
        STANDARD,
        FORCE_GET_DATA_AND_STAT
    }

    void refresh(final RefreshMode mode) throws Exception {
        try {
            ensurePath.ensure(client.getZookeeperClient());
            List<String> children = client.getChildren().usingWatcher(childrenWatcher).forPath(path);
            Collections.sort(children, new Comparator<String>() {
                @Override
                public int compare(String left, String right) {
                    return left.compareTo(right);
                }
            });
            processChildren(children, mode);
        } catch (Exception e) {
            handleException(e);
        }
    }

    void callListeners(final GroupListener.GroupEvent event) {
        LOG.debug(this + ": callListeners(" + event + ")");
        listeners.forEach(
                new Function<GroupListener<T>, Void>() {
                    @Override
                    public Void apply(GroupListener<T> listener) {
                        try {
                            LOG.debug(ZooKeeperGroup.this + ": " + listener.getClass().getSimpleName() + ".groupEvent(" + event + ")");
                            listener.groupEvent(ZooKeeperGroup.this, event);
                        } catch (Exception e) {
                            handleException(e);
                        }
                        return null;
                    }
                }
        );
    }

    Change getDataAndStat(final String fullPath, boolean sendEvent) throws Exception {
        try {
            Stat stat = new Stat();
            byte[] data = client.getData().storingStatIn(stat).usingWatcher(dataWatcher).forPath(fullPath);
            return applyNewData(fullPath, KeeperException.Code.OK.intValue(), stat, data, sendEvent);
        } catch (KeeperException.NoNodeException ignore) {
        }
        return Change.NONE;
    }

    /**
     * Default behavior is just to log the exception
     *
     * @param e the exception
     */
    protected void handleException(Throwable e) {
        if (e instanceof IllegalStateException && "Client is not started".equals(e.getMessage())) {
            LOG.debug("", e);
        } else {
            LOG.error("", e);
        }
    }

    @VisibleForTesting
    protected boolean remove(String fullPath) {
        ChildData data = currentData.remove(fullPath);
        if (data != null) {
            if (fullPath.equals(id)) {
                // this will ensure that we'll register another cluster candidate if ZK path was removed exernally
                state = null;
            }
            return true;
        }
        return false;
    }

    private void handleStateChange(ConnectionState newState) {
        switch (newState) {
            case SUSPENDED:
            case LOST: {
                connected.set(false);
                clear();
                LOG.info(this + ": connection " + newState + ", calling Event(DISCONNECTED) directly");
                EventOperation op = new EventOperation(this, GroupListener.GroupEvent.DISCONNECTED);
                op.invoke();
                break;
            }

            case CONNECTED:
            case RECONNECTED: {
                connected.set(true);
                LOG.info(this + ": connection " + newState + ", offering Refresh(FORCE_GET_DATA_AND_STAT) + Update + Event(CONNECTED)");
                offerOperation(new CompositeOperation(this,
                        new RefreshOperation(this, RefreshMode.FORCE_GET_DATA_AND_STAT),
                        new UpdateOperation<T>(this, state),
                        new EventOperation(this, GroupListener.GroupEvent.CONNECTED)
                ));
                break;
            }
        }
    }

    private void processChildren(List<String> children, RefreshMode mode) throws Exception {
        LOG.debug(this + ": processChildren(size = " + children.size() + ")");
        List<String> fullPaths = Lists.newArrayList(Lists.transform(
                children,
                new Function<String, String>() {
                    @Override
                    public String apply(String child) {
                        return ZKPaths.makePath(path, child);
                    }
                }
        ));
        Set<String> removedNodes = Sets.newHashSet(currentData.keySet());
        removedNodes.removeAll(fullPaths);

        boolean change = false;
        int removed = 0;
        int added = 0;
        int changed = 0;
        for (String fullPath : removedNodes) {
            LOG.debug(this + ":  - removed " + fullPath);
            if (remove(fullPath)) {
                change = true;
                removed++;
            }
        }

        for (String name : children) {
            String fullPath = ZKPaths.makePath(path, name);

            if ((mode == RefreshMode.FORCE_GET_DATA_AND_STAT) || !currentData.containsKey(fullPath)) {
                Change c = getDataAndStat(fullPath, false);
                if (c == Change.ADDED) {
                    LOG.debug(this + ":  - added " + fullPath);
                    added++;
                    change = true;
                } else if (c == Change.MODIFIED) {
                    LOG.debug(this + ":  - modified " + fullPath);
                    changed++;
                    change = true;
                }
            }
        }

        if (change) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(this + ": cluster change (members added: " + added + ", modified: " + changed + ", removed: " + removed + ")");
            }
            offerOperation(new EventOperation(this, GroupListener.GroupEvent.CHANGED));
        }
    }

    private Change applyNewData(String fullPath, int resultCode, Stat stat, byte[] bytes, boolean sendEvent) {
        if (resultCode == KeeperException.Code.OK.intValue()) {
            // otherwise - node must have dropped or something - we should be getting another event
            ChildData<T> data = new ChildData<T>(fullPath, stat, bytes, decode(bytes));
            ChildData<T> previousData = currentData.put(fullPath, data);
            if (previousData == null || previousData.getStat().getVersion() != stat.getVersion()) {
                if (sendEvent) {
                    offerOperation(new EventOperation(this, GroupListener.GroupEvent.CHANGED));
                } else {
                    return previousData == null ? Change.ADDED : Change.MODIFIED;
                }
            }
        }
        return Change.NONE;
    }

    private void mainLoop() {
        while (started.get() && !Thread.currentThread().isInterrupted()) {
            String tn = Thread.currentThread().getName();
            try {
                Operation op = operations.take();
                Thread.currentThread().setName(tn + " [" + op.id() + "]");

                LOG.debug(this + ": invoking " + op);
                op.invoke();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                handleException(e);
            } finally {
                Thread.currentThread().setName(tn);
            }
        }
    }

    private byte[] encode(T state) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MAPPER.writeValue(baos, state);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to decode data", e);
        }
    }

    private T decode(byte[] data) {
        try {
            return MAPPER.readValue(data, clazz);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to decode data", e);
        }
    }

    private void offerOperation(Operation operation) {
        // this check requires proper implementation of equals/hashcode and the goal is to not invoke
        // same operation many times.
        //
        // in case of adding e.g., Event(CHANGED) to a queue where there's e.g., Refresh + Event(CHANGED), the
        // new even won't be added. so it's important that Refresh itself will add Event(CHANGED) if there's a need
        // to do so
        //
        // both contains() and offer() are blocking here, but the combination of them isn't. But it's not a problem here:
        //  1) if operation is just being processed (it's no longer in queue), it'll simply be added
        //  2) if operation is in queue - it won't be added, but the existing operation will be processed later
        //  3) if operation is not in queue - it'll be added
        if (!operations.contains(operation)) {
            LOG.debug(this + ": offering " + operation);
            operations.offer(operation);
        } else {
            LOG.debug(this + ": " + operation + " not offered, similar operation pending");
        }
    }

    public static <T> Map<String, T> members(ObjectMapper mapper, CuratorFramework curator, String path, Class<T> clazz) throws Exception {
        Map<String, T> map = new TreeMap<String, T>();
        List<String> nodes = curator.getChildren().forPath(path);
        for (String node : nodes) {
            byte[] data = curator.getData().forPath(path + "/" + node);
            T val = mapper.readValue(data, clazz);
            map.put(node, val);
        }
        return map;
    }

    public String getId() {
        return id;
    }

    @VisibleForTesting
    void setId(String id) {
        this.id = id;
    }

    /**
     * Returns an indication that the sequential, ephemeral node may be registered more than once for this group
     * @return
     */
    public boolean isUnstable() {
        return unstable.get();
    }

    /**
     * To be used by operations to get their ID.
     * @return
     */
    String nextId() {
        return String.format("%06d", counter.incrementAndGet());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("<ZKGroup ").append(source);
        if (id != null) {
            sb.append(", ").append(id);
        } else {
            sb.append(", ").append(path).append("/?");
        }
        sb.append(", ").append(uuid).append(">");
        return sb.toString();
    }

    /**
     * Whether cluster member was added or updated
     */
    enum Change {
        NONE, ADDED, MODIFIED
    }

}
