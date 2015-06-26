/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.camel;

import io.fabric8.zookeeper.spring.CuratorFactoryBean;
import io.fabric8.zookeeper.spring.ZKServerFactoryBean;
import java.util.List;
import junit.framework.Assert;
import org.apache.camel.CamelContext;
import org.apache.camel.EndpointInject;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.Route;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.util.ServiceHelper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.AbstractJUnit4SpringContextTests;

@ContextConfiguration
public class MasterQuartz2EndpointTest extends AbstractJUnit4SpringContextTests {

    @Autowired
    protected CamelContext camelContext;

    @EndpointInject(uri = "mock:results")
    protected MockEndpoint resultEndpoint;

    // Yeah this sucks.. why does the spring context not get shutdown
    // after each test case?  Not sure!
    @Autowired
    protected ZKServerFactoryBean zkServerBean;

    @Autowired
    protected CuratorFactoryBean zkClientBean;

    @Before
    public void startService() throws Exception {
        ServiceHelper.startService(camelContext);
    }

    @After
    public void afterRun() throws Exception {
        lastServerBean = zkServerBean;
        lastClientBean = zkClientBean;
        ServiceHelper.stopServices(camelContext);
    }

    protected static ZKServerFactoryBean lastServerBean;

    protected static CuratorFactoryBean lastClientBean;

    @AfterClass
    static public void shutDownZK() throws Exception {
        lastClientBean.destroy();
        lastServerBean.destroy();
    }

    @Test
    public void  testEndpoint() throws Exception {

        System.out.println("===== starting test of Master quartz endpoint!");


        resultEndpoint.expectedMinimumMessageCount(2);


        MockEndpoint.assertIsSatisfied(camelContext);

        System.out.println("===== completed test of Master quartz endpoint!");
 
    }
}
