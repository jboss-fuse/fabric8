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
package io.fabric8.cxf.endpoint;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.MBeanServerDelegate;
import javax.management.MBeanServerNotification;
import javax.management.MalformedObjectNameException;
import javax.management.Notification;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;

import org.apache.cxf.Bus;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.endpoint.EndpointImpl;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.endpoint.ServerLifeCycleManager;
import org.apache.cxf.feature.AbstractFeature;
import org.apache.cxf.feature.Feature;
import org.apache.cxf.interceptor.InterceptorProvider;
import org.apache.cxf.management.InstrumentationManager;
import org.apache.cxf.service.factory.AbstractServiceFactoryBean;
import org.apache.cxf.service.factory.FactoryBeanListener;
import org.apache.cxf.service.factory.FactoryBeanListenerManager;


public class ManagedApiFeature extends AbstractFeature {
    private static final Logger LOG = LogUtils.getL7dLogger(ManagedApiFeature.class);

    private static final ObjectName CXF_OBJECT_NAME = objectNameFor("org.apache.cxf:*");

    private NotificationFilter mBeanServerNotificationFilter = new NotificationFilter() {
        @Override
        public boolean isNotificationEnabled(Notification notification) {
            return (notification instanceof MBeanServerNotification) &&
                    CXF_OBJECT_NAME.apply(((MBeanServerNotification) notification).getMBeanName());
        }
    };

    @Override
    public void initialize(Server server, Bus bus) {
        final ManagedApi mApi = new ManagedApi(bus, server.getEndpoint(), server);
        final InstrumentationManager iMgr = bus.getExtension(InstrumentationManager.class);
        if (iMgr != null) {   
            try {
                iMgr.register(mApi);
                final ServerLifeCycleManager slcMgr = bus.getExtension(ServerLifeCycleManager.class);
                if (slcMgr != null) {
                    slcMgr.registerListener(mApi);
                    slcMgr.startServer(server);
                }

                // Register notification listener to propagate unregistration of endpoint MBeans
                final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
                if (mBeanServer == null) {
                    return;
                }
                NotificationListener listener = new NotificationListener() {
                    @Override
                    public void handleNotification(Notification notification, Object handback) {
                        MBeanServerNotification mbsNotification = (MBeanServerNotification) notification;
                        ObjectName objectName = mbsNotification.getMBeanName();
                        String type = mbsNotification.getType();
                        try {
                            if (MBeanServerNotification.UNREGISTRATION_NOTIFICATION.equals(type)
                                    && mApi.isCompanion(objectName)) {
                                if (slcMgr != null) {
                                    slcMgr.unRegisterListener(mApi);
                                }
                                iMgr.unregister(mApi);
                                mBeanServer.removeNotificationListener(MBeanServerDelegate.DELEGATE_NAME, this);
                            }
                        } catch (JMException e) {
                            LOG.log(Level.WARNING, "Unregistering ManagedApi failed.", e);
                        }
                    }
                };
                mBeanServer.addNotificationListener(MBeanServerDelegate.DELEGATE_NAME, listener,
                        mBeanServerNotificationFilter, null);

            } catch (JMException jmex) {
                jmex.printStackTrace();
                LOG.log(Level.WARNING, "Registering ManagedApi failed.", jmex);
            }
        }
    }
    
    @Override
    public void initialize(final Bus bus) {
        FactoryBeanListenerManager factoryBeanListenerManager = bus.getExtension(FactoryBeanListenerManager.class);
        if (factoryBeanListenerManager == null) {
            factoryBeanListenerManager = new FactoryBeanListenerManager(bus);
        }
        factoryBeanListenerManager.addListener(new FactoryBeanListener() {
            @Override
            public void handleEvent(Event arg0, AbstractServiceFactoryBean arg1, Object... arg2) {
                if (arg0.equals(Event.SERVER_CREATED) && (arg2[0] instanceof Server)) {
                    Server server = (Server)arg2[0];
                    initialize(server, bus);
                }
            }
        });
        
    }
    
    @Override
    protected void initializeProvider(InterceptorProvider provider, final Bus bus) {
        if (provider instanceof Endpoint) {
            EndpointImpl endpointImpl = (EndpointImpl)provider;
            List<Feature> features = endpointImpl.getActiveFeatures();
            if (features == null) {
                features = new ArrayList<Feature>();
                features.add(this);
                endpointImpl.initializeActiveFeatures(features);
            } else {
                features.add(this);
            }
        } else if (provider instanceof Bus) {
            FactoryBeanListenerManager factoryBeanListenerManager = bus.getExtension(FactoryBeanListenerManager.class);
            if (factoryBeanListenerManager == null) {
                factoryBeanListenerManager = new FactoryBeanListenerManager(bus);
            }
            factoryBeanListenerManager.addListener(new FactoryBeanListener() {
                @Override
                public void handleEvent(Event arg0, AbstractServiceFactoryBean arg1, Object... arg2) {
                    if (arg0.equals(Event.SERVER_CREATED) && (arg2[0] instanceof Server)) {
                        Server server = (Server)arg2[0];
                        initialize(server, bus);
                    }
                }
            });
        } else {
            List<Feature> features = (List<Feature>)bus.getFeatures();
            if (features == null) {
                features = new ArrayList<Feature>();
                features.add(this);
            } else {
                features.add(this);
            }
        }
    }

    private static ObjectName objectNameFor(String name) {
        try {
            return new ObjectName(name);
        } catch (MalformedObjectNameException e) {
            return null;
        }
    }
}
