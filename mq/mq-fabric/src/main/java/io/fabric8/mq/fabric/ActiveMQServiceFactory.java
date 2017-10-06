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
package io.fabric8.mq.fabric;

import java.beans.PropertyEditorManager;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.jms.ConnectionFactory;

import io.fabric8.api.Container;
import io.fabric8.api.FabricService;
import io.fabric8.groups.Group;
import io.fabric8.groups.GroupListener;
import io.fabric8.mq.fabric.discovery.FabricDiscoveryAgent;
import io.fabric8.utils.NamedThreadFactory;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.BrokerStoppedException;
import org.apache.activemq.broker.LockableServiceSupport;
import org.apache.activemq.broker.TransportConnector;
import org.apache.activemq.network.DiscoveryNetworkConnector;
import org.apache.activemq.network.NetworkConnector;
import org.apache.activemq.spring.SpringBrokerContext;
import org.apache.activemq.spring.Utils;
import org.apache.activemq.store.PersistenceAdapter;
import org.apache.activemq.util.IntrospectionSupport;
import org.apache.activemq.util.ServiceSupport;
import org.apache.curator.framework.CuratorFramework;
import org.apache.xbean.classloader.MultiParentClassLoader;
import org.apache.xbean.spring.context.ResourceXmlApplicationContext;
import org.apache.xbean.spring.context.impl.URIEditor;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.Resource;

import static io.fabric8.mq.fabric.discovery.FabricDiscoveryAgent.ActiveMQNode;

public class ActiveMQServiceFactory  {

    public static final Logger LOG = LoggerFactory.getLogger(ActiveMQServiceFactory.class);
    public static final ThreadLocal<Properties> CONFIG_PROPERTIES = new ThreadLocal<Properties>();
    public static final int SHUTDOWN_TIMEOUT_IN_SECONDS = 60;

    BundleContext bundleContext;

    // Pool management

    private Set<String> ownedPools = new HashSet<String>();

    // Maintain a registry of configuration based on ManagedServiceFactory events.
    private Map<String, ClusteredConfiguration> configurations = new HashMap<String, ClusteredConfiguration>();

    volatile FabricService fabricService;
    private ConfigThread config_thread;
    CuratorFramework curator;

    public ActiveMQServiceFactory() {
        // code that was inlined through all Scala ActiveMQServiceFactory class
        config_thread = new ConfigThread();
        config_thread.setName("ActiveMQ Configuration Watcher");
        config_thread.start();
    }

    /* statics - from Scala object */

    static {
        PropertyEditorManager.registerEditor(URI.class, URIEditor.class);
    }

//    public static void info(String str) {
//        if (LOG.isInfoEnabled()) {
//            LOG.info(str);
//        }
//    }
//
//    public static void info(String str, Object... args) {
//        if (LOG.isInfoEnabled()) {
//            LOG.info(String.format(str, args));
//        }
//    }
//
//    public static void debug(String str) {
//        if (LOG.isDebugEnabled()) {
//            LOG.debug(str);
//        }
//    }
//
//    public static void debug(String str, Object... args) {
//        if (LOG.isDebugEnabled()) {
//            LOG.debug(String.format(str, args));
//        }
//    }
//
//    public static void warn(String str) {
//        if (LOG.isWarnEnabled()) {
//            LOG.warn(str);
//        }
//    }
//
//    public static void warn(String str, Object... args) {
//        if (LOG.isWarnEnabled()) {
//            LOG.warn(String.format(str, args));
//        }
//    }

    public static Dictionary<String, Object> toDictionary(Properties properties) {
        Hashtable<String, Object> answer = new Hashtable<String, Object>();
        for (String k : properties.stringPropertyNames()) {
            answer.put(k, properties.getProperty(k));
        }
        return answer;
    }

    public static <T> T arg_error(String msg) {
        throw new IllegalArgumentException(msg);
    }

    public ServerInfo createBroker(String uri, Properties properties) throws Exception {
        CONFIG_PROPERTIES.set(properties);
        try {
            ClassLoader classLoader = new MultiParentClassLoader("xbean", new URL[0], new ClassLoader[] {
                this.getClass().getClassLoader(),
                BrokerService.class.getClassLoader()
            });
            Thread.currentThread().setContextClassLoader(classLoader);

            Resource resource = Utils.resourceFromString(uri);

            // To work around ENTESB-2761, lets wait up to 10 seconds for the broker.xml
            // to get deployed before trying to start the broker.
            for( int i=0; !resource.exists() && i < 100; i++ ) {
               Thread.sleep(100);
            }

            ResourceXmlApplicationContext ctx = new ResourceXmlApplicationContext(resource) {
                @Override
                protected void initBeanDefinitionReader(XmlBeanDefinitionReader reader) {
                    reader.setValidating(false);
                }
            };

            String[] names = ctx.getBeanNamesForType(BrokerService.class);
            BrokerService broker = null;
            for (String name : names) {
                broker = (BrokerService) ctx.getBean(name, BrokerService.class);
                if (broker != null) {
                    break;
                }
            }
            if (broker == null) {
                arg_error("Configuration did not contain a BrokerService");
            }

            String networks = properties.getProperty("network", "");
            String[] networksTab = networks.split(",");
            for (String name : networksTab) {
                if (!name.isEmpty()) {
                    LOG.info("Adding network connector " + name);
                    NetworkConnector nc = createNetworkConnector(new URI("fabric:" + name),properties);
                    nc.setName("fabric-" + name);
                    if (broker != null) {
                        // and if it's null, then exception was thrown already. It's just IDEA complaining
                        broker.addNetworkConnector(nc);
                    }
                }
            }

            //static network connectors
            String staticNetworks = properties.getProperty("static-network", "");
            networksTab = staticNetworks.split(",");
            for (String name : networksTab) {
                if (!name.isEmpty()) {
                    LOG.info("Adding static network connector " + name);
                    NetworkConnector nc = createNetworkConnector(new URI("static:(" + name + ")"),properties);
                    nc.setName("static-"+ name);
                    if (broker != null) {
                        broker.addNetworkConnector(nc);
                    }
                }
            }

            SpringBrokerContext brokerContext = new SpringBrokerContext();
            brokerContext.setConfigurationUrl(resource.getURL().toExternalForm());
            brokerContext.setApplicationContext(ctx);
            if (broker != null) {
                broker.setBrokerContext(brokerContext);
            }
            return new ServerInfo(ctx, broker, resource);
        } finally {
            CONFIG_PROPERTIES.remove();
        }
    }

    /* now non-static members from Scala class */

    public synchronized boolean can_own_pool(ClusteredConfiguration cc) {
        return cc.pool == null || !ownedPools.contains(cc.pool);
    }

    public synchronized boolean take_pool(ClusteredConfiguration cc) {
        if (cc.pool == null) {
            return true;
        } else {
            if (ownedPools.contains(cc.pool)) {
                return false;
            } else {
                ownedPools.add(cc.pool);
                fire_pool_change(cc);
                return true;
            }
        }
    }

    public synchronized void return_pool(ClusteredConfiguration cc) {
        if (cc.pool != null) {
            ownedPools.remove(cc.pool);
            fire_pool_change(cc);
        }
        cc.pool_enabled = false;
    }

    private void fire_pool_change(final ClusteredConfiguration cc) {
        (new Thread() {
            @Override
            public void run() {
                synchronized (ActiveMQServiceFactory.this) {
                    for (ClusteredConfiguration c : configurations.values()) {
                        if (c != cc && c != null && c.pool != null && c.pool.equals(cc.pool)) {
                            c.update_pool_state();
                        }
                    }
                }
            }
        }).start();
    }

    BrokerService getBrokerService(String name) {
        synchronized (ActiveMQServiceFactory.this) {
            for (ClusteredConfiguration c : configurations.values()) {
                if( name.equals(c.name) ) {
                    if( c.server == null ) {
                        return null;
                    }
                    return c.server.getBroker();
                }
            }
        }
        return null;
    }

    // ManagedServiceFactory implementation

    public synchronized void updated(String pid, Properties properties) throws ConfigurationException {
        try {
            deleted(pid);
            configurations.put(pid, new ClusteredConfiguration(properties));
        } catch (Exception e) {
            ConfigurationException configurationException = new ConfigurationException(null, "Unable to parse ActiveMQ configuration: " + e.getMessage());
            configurationException.initCause(e);
            throw configurationException;
        }
    }

    public synchronized void deleted(String pid) {
        ClusteredConfiguration cc = configurations.remove(pid);
        if (cc != null) {
            try {
                cc.close();
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }


    // Lifecycle
    public synchronized void destroy() throws InterruptedException {
        config_thread.running = false;
        config_thread.interrupt();
        config_thread.join();
        for (String pid : configurations.keySet()) {
            deleted(pid);
        }
    }

    private NetworkConnector createNetworkConnector(URI uri,Properties properties) throws URISyntaxException, IOException {
        NetworkConnector nc = new DiscoveryNetworkConnector(uri);
        Map<String, Object> networkProperties = new HashMap<String, Object>();
        // use default credentials for network connector (if none was specified)
        networkProperties.put("network.userName", "admin");
        networkProperties.put("network.password", properties.getProperty("zookeeper.password"));

        for (String k : properties.stringPropertyNames()) {
            networkProperties.put(k, properties.getProperty(k));
        }

        IntrospectionSupport.setProperties(nc, networkProperties, "network.");
        return nc;
    }

    /**
     * 3 items that define Broker
     */
    private static class ServerInfo {

        private /*_1*/ ResourceXmlApplicationContext context;
        private /*_2*/ BrokerService broker;
        private /*_3*/ Resource resource;

        public ServerInfo(ResourceXmlApplicationContext context, BrokerService broker, Resource resource) {
            this.context = context;
            this.broker = broker;
            this.resource = resource;
        }

        public ResourceXmlApplicationContext getContext() {
            return context;
        }

        public BrokerService getBroker() {
            return broker;
        }

        public Resource getResource() {
            return resource;
        }

    }

    private class ClusteredConfiguration {

        private Properties properties;
        String name;
        private String data;
        private String config;
        private String group;
        private String pool;
        private String[] connectors;
        private boolean replicating;
        private boolean standalone;
        private boolean registerService;
        private boolean configCheck;

        private boolean pool_enabled = false;
        private long lastModified = -1L;

        volatile ServerInfo server;

        private FabricDiscoveryAgent discoveryAgent = null;

        private final AtomicBoolean started = new AtomicBoolean();

        private ExecutorService executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("AMQ"));

        private Future<?> start_future = null;
        private Future<?> stop_future = null;

        private ServiceRegistration<javax.jms.ConnectionFactory> cfServiceRegistration = null;

        ClusteredConfiguration(Properties properties) throws Exception {
            this.properties = properties;

            this.name = properties.getProperty("broker-name");
            if (this.name == null)
                this.name = System.getProperty("runtime.id");

            this.data = properties.getProperty("data");
            if (this.data == null)
                this.data = "data" + System.getProperty("file.separator") + this.name;

            this.config = properties.getProperty("config");
            if (this.config == null)
                ActiveMQServiceFactory.arg_error("config property must be set");

            this.group = properties.getProperty("group");
            if (this.group == null)
                this.group = "default";

            this.pool = properties.getProperty("standby.pool");
            if (this.pool == null)
                this.pool = "default";

            String connectorsProperty = properties.getProperty("connectors", "");
            this.connectors = connectorsProperty.split("\\s");

            this.replicating = "true".equalsIgnoreCase(properties.getProperty("replicating"));
            this.standalone = "true".equalsIgnoreCase(properties.getProperty("standalone"));
            this.registerService = "true".equalsIgnoreCase(properties.getProperty("registerService"));
            this.configCheck = "true".equalsIgnoreCase(properties.getProperty("config.check"));

            // code directly invoked in Scala case class
            ensure_broker_name_is_set();

            if (standalone) {
                if (started.compareAndSet(false, true)) {
                    LOG.info("Standalone broker " + name + " is starting.");
                    start();
                }
            } else {
                updateCurator(curator);
            }
        }

        private void ensure_broker_name_is_set() {
            if (!properties.containsKey("broker-name")) {
                properties.setProperty("broker-name", name);
            }
            if (!properties.containsKey("data")) {
                properties.setProperty("data", data);
            }
        }

        public void update_pool_state() {
            synchronized (ActiveMQServiceFactory.this) {
                boolean value = can_own_pool(this);
                if (pool_enabled != value) {
                    try {
                        pool_enabled = value;
                        if (value) {
                            if (pool != null) {
                                LOG.info("Broker " + name + " added to pool " + pool +".");
                            }
                            discoveryAgent.start();
                        } else {
                            if (pool != null) {
                                LOG.info("Broker " + name + " removed from pool " + pool + ".");
                            }
                            discoveryAgent.stop();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }
            }
        }

        public void osgiRegister(BrokerService broker) {
            final javax.jms.ConnectionFactory connectionFactory = new ActiveMQConnectionFactory("vm://" + broker.getBrokerName() + "?create=false");
            Hashtable<String, String> properties = new Hashtable<String, String>();
            properties.put("name", broker.getBrokerName());
            cfServiceRegistration = bundleContext.registerService(ConnectionFactory.class/*.getName()*/, connectionFactory, properties);
            LOG.debug("registerService of type " + javax.jms.ConnectionFactory.class.getName()  + " as: " + connectionFactory + " with name: " + broker.getBrokerName() + "; " + cfServiceRegistration);
        }

        public void osgiUnregister(BrokerService broker) {
            if (cfServiceRegistration != null) {
                cfServiceRegistration.unregister();
            }
            LOG.debug("unregister connection factory for: " + broker.getBrokerName() + "; " + cfServiceRegistration);
        }

        private void start() {
            if (start_future == null || start_future.isDone()) {
                LOG.info("Broker " + name + " is being started.");
                start_future = executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        boolean started = false;
                        while (!started) {
                            try {
                                doStart();
                                if (server != null && server.getResource() != null) {
                                    lastModified = server.getResource().lastModified();
                                }
                                started = true;
                            } catch (BrokerStoppedException expected) {
                                break;
                            } catch (Throwable e) {
                                if (start_future.isCancelled() || Thread.currentThread().isInterrupted()) {
                                    LOG.info("Broker " + name + " interrupted while starting");
                                    break;
                                }
                                LOG.info("Broker " + name + " failed to start.  Will try again in 10 seconds");
                                LOG.error("Exception on start: " + e, e);
                                try {
                                    Thread.sleep(1000 * 10);
                                } catch (InterruptedException ignore) {
                                    LOG.info("Broker " + name + " interrupted while starting");
                                    break;
                                }
                            }
                        }
                    }
                });
            }
        }

        private void doStart() throws Exception {
            // If we are in a fabric, let pass along the zk password in the props.
            FabricService fs = fabricService;
            if (fs != null) {
                Container container = fs.getCurrentContainer();
                if (!properties.containsKey("container.id")) {
                    properties.setProperty("container.id", container.getId());
                }
                if (!properties.containsKey("container.ip")) {
                    properties.setProperty("container.ip", container.getIp());
                }
                if (!properties.containsKey("zookeeper.url")) {
                    properties.setProperty("zookeeper.url", fs.getZookeeperUrl());
                }
                if (!properties.containsKey("zookeeper.password")) {
                    properties.setProperty("zookeeper.password", fs.getZookeeperPassword());
                }
            }
            // ok boot up the server..
            LOG.info("booting up a broker from: " + config);
            server = createBroker(config, properties);
            // configure ports
            for (TransportConnector t : server.getBroker().getTransportConnectors()) {
                String portKey = t.getName() + "-port";
                if (properties.containsKey(portKey)) {
                    URI template = t.getUri();
                    t.setUri(new URI(template.getScheme(), template.getUserInfo(), template.getHost(),
                        Integer.valueOf("" + properties.get(portKey)),
                        template.getPath(), template.getQuery(), template.getFragment()));
                }
            }

            server.getBroker().start();
            LOG.info("Broker " + name + " has started.");

            server.getBroker().waitUntilStarted();
            server.getBroker().addShutdownHook(new Runnable() {
                @Override
                public void run() {
                    // Start up the server again if it shutdown.  Perhaps
                    // it has lost a Locker and wants a restart.
                    if (started.get() && server != null) {
                        if (server.getBroker().isRestartAllowed() && server.getBroker().isRestartRequested()) {
                            LOG.info("Restarting broker '" + name + "' after shutdown on restart request");
                            if (!standalone) {
                                discoveryAgent.setServices(new String[0]);
                            }
                            start();
                        } else if (stop_future == null || stop_future.isDone()) {
                            // abnormal exit
                            LOG.info("Broker '" + name + "' shut down, giving up being master");
                            try {
                                updateCurator(curator);
                            } catch (Exception e) {
                                throw new RuntimeException(e.getMessage(), e);
                            }
                        }
                    }
                }
            });

            // we only register once broker startup has completed.
            if (replicating) {
                discoveryAgent.start();
            }

            // Update the advertised endpoint URIs that clients can use.
            if (!standalone || replicating) {
                registerConnectors();
            }

            if (registerService) {
                osgiRegister(server.getBroker());
            }
        }

        private void registerConnectors() throws Exception {
            List<String> services = new LinkedList<String>();
            for (String name : connectors) {
                TransportConnector connector = server.getBroker().getConnectorByName(name);
                if (connector == null) {
                    LOG.warn("ActiveMQ broker '" + server.getBroker().getBrokerName() + "' does not have a connector called '" + name + "'");
                } else {
                    String ip = (String) properties.get("container.ip");
                    if( ip==null ) {
                        ip = "${zk:" + System.getProperty("runtime.id") + "/ip}";
                    }
                    services.add(connector.getConnectUri().getScheme() + "://" + ip + ":" + connector.getPublishableConnectURI().getPort());
                }
            }
            discoveryAgent.setServices(services.toArray(new String[services.size()]));
        }

        public void close() throws Exception {
            LOG.debug(this + " close");
            synchronized (ActiveMQServiceFactory.this) {
                if (pool_enabled) {
                    return_pool(this);
                }
                if (discoveryAgent != null) {
                    discoveryAgent.stop();
                }
            }
            disconnect();
            // we need to let any possible stop task execute
            executor.shutdown();
            executor.awaitTermination(SHUTDOWN_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        }

        public void stop() throws ExecutionException, InterruptedException {
            synchronized (ActiveMQServiceFactory.this) {
                interruptAndWaitForStart();
                if (stop_future == null || stop_future.isDone()) {
                    stop_future = executor.submit(new Runnable() {
                        @Override
                        public void run() {
                            doStop();
                        }
                    });
                }
            }
        }

        private void doStop() {
            final ServerInfo s = server; // working with a volatile
            if (s != null) {
                try {
                    s.getBroker().stop();
                    s.getBroker().waitUntilStopped();
                    if (!standalone || replicating) {
                        // clear out the services as we are no longer alive
                        discoveryAgent.setServices(new String[0]);
                    }
                    if (registerService) {
                        osgiUnregister(s.getBroker());
                    }
                } catch (Throwable e) {
                    LOG.debug("Exception on stop: " + e.getMessage(),  e);
                }
                try {
                    s.getContext().close();
                } catch (Throwable e) {
                    LOG.debug("Exception on close: " + e.getMessage(), e);
                }
                server = null;
            }
        }

        private void waitForStop() throws ExecutionException, InterruptedException {
            if (stop_future != null) {
                try {
                    stop_future.get(SHUTDOWN_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    LOG.error("Unable to shutdown ActiveMQ in the allotted {} seconds.", SHUTDOWN_TIMEOUT_IN_SECONDS);
                } catch (CancellationException ignore) {
                }
            }
        }

        private void interruptAndWaitForStart() throws ExecutionException, InterruptedException {
            if (start_future != null && !start_future.isDone()) {
                start_future.cancel(false);
                long timeOutSeconds = SHUTDOWN_TIMEOUT_IN_SECONDS;
                if (server != null && server.broker != null) {
                    // slave possibly blocked on store lock, wait for store locker start before calling stop
                    try {

                        PersistenceAdapter persistenceAdapter = server.broker.getPersistenceAdapter();
                        if (persistenceAdapter != null && persistenceAdapter instanceof LockableServiceSupport) {
                            LockableServiceSupport lockableServiceSupport = (LockableServiceSupport) persistenceAdapter;
                            if (lockableServiceSupport.isUseLock() && lockableServiceSupport.getLocker() instanceof ServiceSupport) {
                                ServiceSupport lockService = ((ServiceSupport)lockableServiceSupport.getLocker());
                                while (!lockService.isStarted() && timeOutSeconds > 0) {
                                    TimeUnit.SECONDS.sleep(1);
                                    timeOutSeconds--;
                                }
                                LOG.info("broker stop on active async start, blocked on store lock:" + lockService.isStarted());
                                server.broker.stop();
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("Call to stop failed with exception:" + e.getLocalizedMessage());
                    }
                }
                try {
                   start_future.get(timeOutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    LOG.error("Unable to cancel startup ActiveMQ in the allotted {} seconds.", SHUTDOWN_TIMEOUT_IN_SECONDS);
                } catch (CancellationException ignore) {
                }
            }
        }

        public void updateCurator(CuratorFramework curator) throws Exception {
            if (!standalone) {
                synchronized (ActiveMQServiceFactory.this) {
                    if (discoveryAgent != null) {
                        discoveryAgent.stop();
                        discoveryAgent = null;
                        if (started.compareAndSet(true, false)) {
                            LOG.info("Lost zookeeper service for broker " + name + ", stopping the broker.");
                            stop();
                            waitForStop();
                            return_pool(this);
                        }
                    }
                    waitForStop();
                    if (curator != null) {
                        LOG.info("Found zookeeper service for broker " + name + ".");
                        discoveryAgent = new FabricDiscoveryAgent();
                        discoveryAgent.setAgent(System.getProperty("runtime.id"));
                        discoveryAgent.setId(name);
                        discoveryAgent.setGroupName(group);
                        discoveryAgent.setCurator(curator);
                        if (replicating) {
                            if (started.compareAndSet(false, true)) {
                                LOG.info("Replicating broker " + name + " is starting.");
                                start();
                            }
                        } else {
                            discoveryAgent.getGroup().add(new GroupListener<ActiveMQNode>() {
                                @Override
                                public void groupEvent(Group<ActiveMQNode> group, GroupEvent event) {
                                    try {
                                        LOG.trace("Event:" + event + ", started:" + started.get());
                                        if (event.equals(GroupEvent.CONNECTED) || event.equals(GroupEvent.CHANGED)) {
                                            if (discoveryAgent.getGroup().isMaster(name)) {
                                                if (started.compareAndSet(false, true)) {
                                                    if (take_pool(ClusteredConfiguration.this)) {
                                                        LOG.info("Broker " + name + " is now the master, starting the broker.");
                                                        start();
                                                    } else {
                                                        update_pool_state();
                                                        started.set(false);
                                                    }
                                                } else {
                                                    if (discoveryAgent.getServices().isEmpty() && server != null && server.getBroker() != null && server.getBroker().isStarted()) {
                                                        LOG.info("Reconnected to the group " + name);
                                                        registerConnectors();
                                                    }
                                                }
                                            } else {
                                                if (started.compareAndSet(true, false)) {
                                                    return_pool(ClusteredConfiguration.this);
                                                    LOG.info("Broker " + name + " is now a slave, stopping the broker.");
                                                    stop();
                                                } else {
                                                    if (event.equals(GroupEvent.CHANGED)) {
                                                        LOG.info("Broker " + name + " is slave");
                                                        if (!discoveryAgent.getServices().isEmpty()) {
                                                            discoveryAgent.setServices(new String[0]);
                                                        }
                                                    }
                                                }
                                            }
                                        } else if (event.equals(GroupEvent.DISCONNECTED)) {
                                            LOG.info("Disconnected from the group " + name);
                                            disconnect();
                                        }
                                    } catch (Exception e) {
                                        throw new RuntimeException(e.getMessage(), e);
                                    }
                                }
                            });

                            LOG.info("Broker " + name + " is waiting to become the master");
                            update_pool_state();
                        }
                    } else {
                        LOG.info("Lost zookeeper service for broker " + name);
                        disconnect();
                    }
                }
            }
        }

        private void disconnect() throws Exception {
            if (started.compareAndSet(true, false)) {
              return_pool(ClusteredConfiguration.this);
              LOG.info("Stopping the broker " + name + ".");
              stop();
            } else {
              LOG.info("Disconnecting the broker " + name + ".");
              discoveryAgent.setServices(new String[0]);
              pool_enabled = false;
            }
        }

    }

    private class ConfigThread extends Thread {

        private boolean running = true;

        @Override
        public void run() {
            while (running) {
                for (ClusteredConfiguration c: configurations.values()) {
                    try {
                        if (c.configCheck && c.lastModified != -1 && c.server != null) {
                            long lm = c.server.getResource().lastModified();
                            if (lm != c.lastModified) {
                                c.lastModified = lm;
                                LOG.info("updating " + c.properties);
                                updated((String) c.properties.get("service.pid"), c.properties);
                            }
                        }
                    } catch (Throwable t) {
                        // ?
                    }
                }
                try {
                    Thread.sleep(5 * 1000);
                } catch (InterruptedException e) {
                    // ?
                }
            }
        }

    }

}
