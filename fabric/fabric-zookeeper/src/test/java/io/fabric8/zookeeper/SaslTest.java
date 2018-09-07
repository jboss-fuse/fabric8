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
package io.fabric8.zookeeper;

import java.io.IOException;
import java.security.Principal;
import java.security.Provider;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslClientFactory;
import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslServerFactory;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.security.jca.Providers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * https://cwiki.apache.org/confluence/display/ZOOKEEPER/Server-Server+mutual+authentication
 */
public class SaslTest {

    public static Logger LOG = LoggerFactory.getLogger(SaslTest.class);

    private static String JAAS_APPLICATION_NAME = "quorum-auth";
    private static Configuration configuration;

    @BeforeClass
    public static void init() {
        configuration = Configuration.getConfiguration();
        Configuration.setConfiguration(new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                if (JAAS_APPLICATION_NAME.equals(name)) {
                    return new AppConfigurationEntry[] {
                            new AppConfigurationEntry(MySpecialLoginModule.class.getName(),
                                    AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                                    new HashMap<String, Object>())
                    };
                }
                return null;
            }
        });
    }

    @AfterClass
    public static void cleanup() {
        Configuration.setConfiguration(configuration);
    }

    @Test
    public void saslProviders() throws Exception {

        Set<String> providers = new TreeSet<>();
        Set<String> serviceTypes = new TreeSet<>();
        for (Provider p : Providers.getProviderList().providers()) {
            providers.add(p.getName());
            for (Provider.Service s : p.getServices()) {
                serviceTypes.add(s.getType());
            }
        }
        LOG.info("Providers:");
        for (String p : providers) {
            LOG.info(" - {}", p);
        }
        LOG.info("Service types:");
        for (String st : serviceTypes) {
            LOG.info(" - {}", st);
        }

        LOG.info("SaslClientFactory providers / algorithms:");
        for (Provider p : Providers.getProviderList().providers()) {
            for (Provider.Service s : p.getServices()) {
                if ("SaslClientFactory".equals(s.getType())) {
                    LOG.info(" - {} / {}", s.getProvider().getName(), s.getAlgorithm());
                }
            }
        }
        LOG.info("SaslServerFactory providers / algorithms:");
        for (Provider p : Providers.getProviderList().providers()) {
            for (Provider.Service s : p.getServices()) {
                if ("SaslServerFactory".equals(s.getType())) {
                    LOG.info(" - {} / {}", s.getProvider().getName(), s.getAlgorithm());
                }
            }
        }
        LOG.info("MessageDigest providers / algorithms:");
        for (Provider p : Providers.getProviderList().providers()) {
            for (Provider.Service s : p.getServices()) {
                if ("MessageDigest".equals(s.getType())) {
                    LOG.info(" - {} / {}", s.getProvider().getName(), s.getAlgorithm());
                }
            }
        }
    }

    @Test
    public void howSaslWorks() throws Exception {
        for (Enumeration<SaslServerFactory> e = Sasl.getSaslServerFactories(); e.hasMoreElements(); ) {
            SaslServerFactory ssf = e.nextElement();
            System.out.println("SSF: " + ssf);
        }
        for (Enumeration<SaslClientFactory> e = Sasl.getSaslClientFactories(); e.hasMoreElements(); ) {
            SaslClientFactory scf = e.nextElement();
            System.out.println("SCF: " + scf);
        }

        // com.sun.security.sasl.digest.DigestMD5Server.serverRealms
        final String serverRealm = "zk-quorum-sasl-md5";

        final SaslServer saslServer = Sasl.createSaslServer("DIGEST-MD5",
                "zookeeper-quorum", serverRealm, null, new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                for (Callback callback : callbacks) {
                    // only password callback is needed here
                    if (callback instanceof PasswordCallback) {
                        ((PasswordCallback) callback).setPassword("x".toCharArray());
                    } else if (callback instanceof AuthorizeCallback) {
                        ((AuthorizeCallback) callback).setAuthorized(true);
                        ((AuthorizeCallback) callback).setAuthorizedID("me");
                    }
                }
            }
        });

        SaslClient saslClient = Sasl.createSaslClient(new String[] { "DIGEST-MD5" },
                "me", // com.sun.security.sasl.digest.DigestMD5Base.authzid
                "zookeeper-quorum", serverRealm, null, new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                for (Callback callback : callbacks) {
                    if (callback instanceof NameCallback) {
                        ((NameCallback) callback).setName("me");
                    } else if (callback instanceof RealmCallback) {
                        ((RealmCallback) callback).setText(serverRealm);
                    } else if (callback instanceof PasswordCallback) {
                        ((PasswordCallback) callback).setPassword("x".toCharArray());
                    }
                }
            }
        });

        // server to client and client to server
        final BlockingQueue<byte[]> sc = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> cs = new LinkedBlockingQueue<>();

        // run server
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (!saslServer.isComplete()) {
                    try {
                        byte[] msg = cs.take();
                        // server generates something like:
                        // realm="zk-quorum-sasl-md5",\
                        // nonce="LPdZ/iyk8Bi2NMqMGF/hsYqNL4FyATS/KCbGvIr9",\
                        // charset=utf-8,\
                        // algorithm=md5-sess
                        byte[] response = saslServer.evaluateResponse(msg);
                        sc.put(response);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e.getMessage(), e);
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                }
                LOG.info("SASL Server complete");
            }
        }).start();

        // client - establish the client's JAAS subject
        LoginContext loginContext = new LoginContext(JAAS_APPLICATION_NAME, new CallbackHandler() {
            @Override
            public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                for (Callback callback : callbacks) {
                    if (callback instanceof NameCallback) {
                        ((NameCallback) callback).setName("karaf.name");
                    }
                }
            }
        });
        loginContext.login();
        Subject iam = loginContext.getSubject();
        LOG.info("Client subject: " + iam.getPrincipals());

        // client sends initial SASL token, waits for response and enters SASL client loop
        // ZK quorum learner sends org.apache.zookeeper.server.quorum.auth.QuorumAuth.QUORUM_AUTH_MAGIC_NUMBER
        cs.put(new byte[0]); // empty array required by DIGEST-MD5
        byte[] challenge = sc.take();

        byte[] responseToSend = saslClient.evaluateChallenge(challenge);
        // client side of sasl changes digest-md5 challenge to:
        // charset=utf-8,\
        // username="me",\
        // realm="zk-quorum-sasl-md5",\
        // nonce="LPdZ/iyk8Bi2NMqMGF/hsYqNL4FyATS/KCbGvIr9",\
        // nc=00000001,\
        // cnonce="UojLgDyZviGV8bgocO6U4rZdJlf3G2ZjradZuBPW",\
        // digest-uri="zookeeper-quorum/zk-quorum-sasl-md5",\
        // maxbuf=65536,\
        // response=6bfa46e9cb433f648e247928bf59e2b0,\
        // qop=auth,\
        // authzid="me"
        cs.put(responseToSend);

        byte[] confirmation = sc.take();
        // we should get something like this:
        // rspauth=587c354ad58e464187b05854d6fa7709

        assertFalse(saslClient.isComplete());
        byte[] finalResponse = saslClient.evaluateChallenge(confirmation);
        assertTrue(saslClient.isComplete());
        assertNull(finalResponse);
    }

    public static class MySpecialLoginModule implements LoginModule {

        private Subject subject;
        private CallbackHandler callbackHandler;
        private String name;

        @Override
        public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState, Map<String, ?> options) {
            this.subject = subject;
            this.callbackHandler = callbackHandler;
        }

        /**
         * login phase 1) This method saves the result of the authentication attempt as private state
         * within the LoginModule.
         * @return
         * @throws LoginException
         */
        @Override
        public boolean login() throws LoginException {
            LOG.info("login()");
            try {
                NameCallback nameCallback = new NameCallback("Username: ");
                callbackHandler.handle(new Callback[] { nameCallback });
                this.name = nameCallback.getName();
            } catch (IOException | UnsupportedCallbackException e) {
                LOG.error(e.getMessage(), e);
            }

            return true;
        }

        /**
         * login phase 2) Called if the LoginContext's overall authentication succeeded
         * @return
         * @throws LoginException
         */
        @Override
        public boolean commit() throws LoginException {
            LOG.info("commit()");
            subject.getPrincipals().add(new Principal() {
                @Override
                public String getName() {
                    return MySpecialLoginModule.this.name;
                }

                @Override
                public String toString() {
                    return "Principal \"" + MySpecialLoginModule.this.name + "\"";
                }
            });
            return true;
        }

        @Override
        public boolean abort() throws LoginException {
            return false;
        }

        @Override
        public boolean logout() throws LoginException {
            return false;
        }
    }

}
