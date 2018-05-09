/*
 *  Copyright 2016 Red Hat, Inc.
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
package io.fabric8.jolokia;

import java.io.IOException;
import javax.management.ObjectName;

import org.jolokia.config.ConfigKey;
import org.jolokia.config.Configuration;
import org.jolokia.restrictor.AllowAllRestrictor;
import org.jolokia.restrictor.DenyAllRestrictor;
import org.jolokia.restrictor.Restrictor;
import org.jolokia.restrictor.RestrictorFactory;
import org.jolokia.util.HttpMethod;
import org.jolokia.util.NetworkUtil;
import org.jolokia.util.RequestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ported from Hawtio.
 * <p>
 * Jolokia restrictor that protects MBean server invocation inside Jolokia based on RBAC provided by
 * org.apache.karaf.management.JMXSecurityMBean.
 */
public class RBACRestrictor implements Restrictor {

    private static final Logger LOG = LoggerFactory.getLogger(RBACRestrictor.class);

    protected Restrictor delegate;
    protected RBACMBeanInvoker mBeanInvoker;

    public RBACRestrictor(Configuration config) {
        this(NetworkUtil.replaceExpression(config.get(ConfigKey.POLICY_LOCATION)));
    }

    public RBACRestrictor(String policyLocation) {
        initDelegate(policyLocation);
        this.mBeanInvoker = new RBACMBeanInvoker();
    }

    protected void initDelegate(String policyLocation) {
        try {
            this.delegate = RestrictorFactory.lookupPolicyRestrictor(policyLocation);
            if (this.delegate != null) {
                LOG.debug("Delegate - Using policy access restrictor {}", policyLocation);
            } else {
                LOG.debug("Delegate - No policy access restrictor found, access to any MBean is allowed");
                this.delegate = new AllowAllRestrictor();
            }
        } catch (IOException e) {
            LOG.error("Delegate - Error while accessing access policy restrictor at " + policyLocation +
                    ". Denying all access to MBeans for security reasons. Exception: " + e, e);
            this.delegate = new DenyAllRestrictor();
        }
    }

    @Override
    public boolean isOperationAllowed(ObjectName objectName, String operation) {
        boolean allowed = delegate.isOperationAllowed(objectName, operation);
        if (allowed) {
            allowed = mBeanInvoker.canInvoke(objectName, operation);
        }
        LOG.debug("isOperationAllowed(objectName = {}, operation = {}) = {}",
            new Object[] { objectName, operation, allowed });
        return allowed;
    }

    @Override
    public boolean isAttributeReadAllowed(ObjectName objectName, String attribute) {
        boolean allowed = delegate.isAttributeReadAllowed(objectName, attribute);
        if (allowed) {
            allowed = mBeanInvoker.isReadAllowed(objectName, attribute);
        }
        LOG.debug("isAttributeReadAllowed(objectName = {}, attribute = {}) = {}",
            new Object[] { objectName, attribute, allowed });
        return allowed;
    }

    @Override
    public boolean isAttributeWriteAllowed(ObjectName objectName, String attribute) {
        boolean allowed = delegate.isAttributeWriteAllowed(objectName, attribute);
        if (allowed) {
            allowed = mBeanInvoker.isWriteAllowed(objectName, attribute);
        }
        LOG.debug("isAttributeWriteAllowed(objectName = {}, attribute = {}) = {}",
            new Object[] { objectName, attribute, allowed });
        return allowed;
    }

    @Override
    public boolean isHttpMethodAllowed(HttpMethod method) {
        boolean allowed = delegate.isHttpMethodAllowed(method);
        LOG.trace("isHttpMethodAllowed(method = {}) = {}", method, allowed);
        return allowed;
    }

    @Override
    public boolean isTypeAllowed(RequestType type) {
        boolean allowed = delegate.isTypeAllowed(type);
        LOG.trace("isTypeAllowed(type = {}) = {}", type, allowed);
        return allowed;
    }

    @Override
    public boolean isRemoteAccessAllowed(String... hostOrAddress) {
        boolean allowed = delegate.isRemoteAccessAllowed(hostOrAddress);
        LOG.trace("isRemoteAccessAllowed(hostOrAddress = {}) = {}",
            hostOrAddress, allowed);
        return allowed;
    }

    @Override
    public boolean isOriginAllowed(String origin, boolean strictCheck) {
        boolean allowed = delegate.isOriginAllowed(origin, strictCheck);
        LOG.trace("isOriginAllowed(origin = {}, strictCheck = {}) = {}",
            new Object[] { origin, strictCheck, allowed });
        return allowed;
    }

}
