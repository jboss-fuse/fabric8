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
package io.fabric8.insight.jetty;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.jmx.ObjectMBean;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import javax.management.DynamicMBean;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.modelmbean.ModelMBean;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Container class for the MBean instances
 */
public class MBeanContainerWrapper extends MBeanContainer
{

    private final static Logger LOG = Log.getLogger(MBeanContainer.class.getName());

    private static Class[] OBJ_ARG = new Class[]{Object.class};

    public MBeanContainerWrapper(MBeanServer server) {
        super(null);
        this._server = server;
    }

    private final MBeanServer _server;
    private final WeakHashMap<Object, ObjectName> _beans = new WeakHashMap<Object, ObjectName>();
    private final HashMap<String, Integer> _unique = new HashMap<String, Integer>();
    private String _domain = null;

    /**
     * Lookup an object name by instance
     *
     * @param object instance for which object name is looked up
     * @return object name associated with specified instance, or null if not found
     */
    public synchronized ObjectName findMBean(Object object)
    {
        ObjectName bean = _beans.get(object);
        return bean == null ? null : bean;
    }

    /**
     * Lookup an instance by object name
     *
     * @param oname object name of instance
     * @return instance associated with specified object name, or null if not found
     */
    public synchronized Object findBean(ObjectName oname)
    {
        for (Map.Entry<Object, ObjectName> entry : _beans.entrySet())
        {
            ObjectName bean = entry.getValue();
            if (bean.equals(oname))
                return entry.getKey();
        }
        return null;
    }

    /**
     * Retrieve instance of MBeanServer used by container
     *
     * @return instance of MBeanServer
     */
    public MBeanServer getMBeanServer()
    {
        return _server;
    }

    /**
     * Set domain to be used to add MBeans
     *
     * @param domain domain name
     */
    public void setDomain(String domain)
    {
        _domain = domain;
    }

    /**
     * Retrieve domain name used to add MBeans
     *
     * @return domain name
     */
    public String getDomain()
    {
        return _domain;
    }


    @Override
    public synchronized void beanAdded(Container parent, Object obj)
    {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        try {
            Class clazz = obj.getClass();
            if (obj.getClass().getName().startsWith("org.ops4j.pax.web.")) {
                clazz = obj.getClass().getSuperclass();
            }
            Thread.currentThread().setContextClassLoader(clazz.getClassLoader());
            beanAdded(parent, obj, clazz);
        } finally {
            Thread.currentThread().setContextClassLoader(tccl);
        }
    }

    public void beanAdded(Container parent, Object obj, Class clazz)
    {
        LOG.debug("beanAdded {}->{}",parent,obj);

        // Is their an object name for the parent
        ObjectName pname=null;
        if (parent!=null)
        {
            pname=_beans.get(parent);
            if (pname==null)
            {
                // create the parent bean
                beanAdded(null,parent);
                pname=_beans.get(parent);
            }
        }

        // Does an mbean already exist?
        if (obj == null || _beans.containsKey(obj))
            return;

        try
        {
            // Create an MBean for the object
            Object mbean = mbeanFor(obj, clazz);
            if (mbean == null)
                return;


            ObjectName oname = null;
            if (mbean instanceof ObjectMBean)
            {
                try {
                    Method method = ObjectMBean.class.getDeclaredMethod("setMBeanContainer", MBeanContainer.class);
                    method.setAccessible(true);
                    method.invoke(mbean, this);
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
//                ((ObjectMBean)mbean).setMBeanContainer(this);
                oname = ((ObjectMBean)mbean).getObjectName();
            }

            //no override mbean object name, so make a generic one
            if (oname == null)
            {
                //if no explicit domain, create one
                String domain = _domain;
                if (domain == null)
                    domain = clazz.getPackage().getName();

                String type = clazz.getName().toLowerCase();
                int dot = type.lastIndexOf('.');
                if (dot >= 0)
                    type = type.substring(dot + 1);

                String context = (mbean instanceof ObjectMBean)?makeName(((ObjectMBean)mbean).getObjectContextBasis()):null;
                String name = (mbean instanceof ObjectMBean)?makeName(((ObjectMBean)mbean).getObjectNameBasis()):null;

                StringBuffer buf = new StringBuffer();
                if (pname!=null)
                {
                    buf.append("parent=")
                            .append(pname.getKeyProperty("type"))
                            .append("-");

                    if (pname.getKeyProperty("context")!=null)
                        buf.append(pname.getKeyProperty("context")).append("-");

                    buf.append(pname.getKeyProperty("id"))
                            .append(",");
                }
                buf.append("type=").append(type);
                if (context != null && context.length()>1)
                {
                    buf.append(buf.length()>0 ? ",":"");
                    buf.append("context=").append(context);
                }
                if (name != null && name.length()>1)
                {
                    buf.append(buf.length()>0 ? ",":"");
                    buf.append("name=").append(name);
                }

                String basis = buf.toString();
                Integer count = _unique.get(basis);
                count = count == null ? 0 : 1 + count;
                _unique.put(basis, count);

                oname = ObjectName.getInstance(domain + ":" + basis + ",id=" + count);
            }

            ObjectInstance oinstance = _server.registerMBean(mbean, oname);
            LOG.debug("Registered {}", oinstance.getObjectName());
            _beans.put(obj, oinstance.getObjectName());

        }
        catch (Exception e)
        {
            LOG.warn("bean: " + obj, e);
        }
    }

    @Override
    public void beanRemoved(Container parent, Object obj)
    {
        LOG.debug("beanRemoved {}",obj);
        ObjectName bean = _beans.remove(obj);

        if (bean != null)
        {
            try
            {
                _server.unregisterMBean(bean);
                LOG.debug("Unregistered {}", bean);
            }
            catch (javax.management.InstanceNotFoundException e)
            {
                LOG.ignore(e);
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
    }

    /**
     * @param basis name to strip of special characters.
     * @return normalized name
     */
    public String makeName(String basis)
    {
        if (basis==null)
            return null;
        return basis.replace(':', '_').replace('*', '_').replace('?', '_').replace('=', '_').replace(',', '_').replace(' ', '_');
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out,this);
        ContainerLifeCycle.dump(out, indent, _beans.entrySet());
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    public void destroy()
    {
        for (ObjectName oname : _beans.values())
            if (oname!=null)
            {
                try
                {
                    _server.unregisterMBean(oname);
                }
                catch (MBeanRegistrationException | InstanceNotFoundException e)
                {
                    LOG.warn(e);
                }
            }
    }

    static Object mbeanFor(Object o, Class clazz)
    {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        try
        {
            Class oClass = clazz;
            Object mbean = null;

            while (/*mbean == null && */oClass != null)
            {
                Thread.currentThread().setContextClassLoader(oClass.getClassLoader());

                String pName = oClass.getPackage().getName();
                String cName = oClass.getName().substring(pName.length() + 1);
                String mName = pName + ".jmx." + cName + "MBean";

                try
                {
                    Class<?> mClass = (Object.class.equals(oClass))?oClass=ObjectMBean.class:Loader.loadClass(oClass,mName);
                    if (LOG.isDebugEnabled())
                        LOG.debug("mbeanFor " + o + " mClass=" + mClass);

                    try
                    {
                        Constructor<?> constructor = mClass.getConstructor(OBJ_ARG);
                        mbean=constructor.newInstance(o);
                    }
                    catch(Exception e)
                    {
                        LOG.ignore(e);
                        if (ModelMBean.class.isAssignableFrom(mClass))
                        {
                            mbean=mClass.newInstance();
                            ((ModelMBean)mbean).setManagedResource(o, "objectReference");
                        }
                    }

                    if (mbean instanceof DynamicMBean)
                    {
                        ((DynamicMBean) mbean).getMBeanInfo();
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("mbeanFor " + o + " is " + mbean);
                    return mbean;
                }
                catch (ClassNotFoundException e)
                {
                    // The code below was modified to fix bugs 332200 and JETTY-1416
                    // The issue was caused by additional information added to the
                    // message after the class name when running in Apache Felix,
                    // as well as before the class name when running in JBoss.
                    if (e.getMessage().contains(mName))
                        LOG.ignore(e);
                    else
                        LOG.warn(e);
                }
                catch (Exception | Error e)
                {
                    LOG.warn(e);
                    mbean = null;
                }

                oClass = oClass.getSuperclass();
            }
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }
        return null;
    }

}
