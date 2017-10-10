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
package io.fabric8.insight.elasticsearch.auth.plugin.auth.integration.jaas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.io.IOException;
import java.security.Principal;
import java.util.Map;

public class TestLoginModule implements LoginModule {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestLoginModule.class);
    public static final String USER1 = "admin";
    public static final String PASSWORD1 = "admin";
    public static final String ROLES1 = "admin,testRole";
    public static final String USER2 = "user";
    public static final String ROLES2 = "testRole";

    private Map<String, String> options;
    private CallbackHandler callbackHandler;
    private Map<String, String> sharedState;
    private Subject subject;

    private String name;
    private String password;
    private boolean succeeded;

    private TestPrincipal principal;

    public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState, Map<String, ?> options) {
        this.subject = subject;
        this.callbackHandler = callbackHandler;
        this.sharedState = (Map<String, String>) sharedState;
        this.options = (Map<String, String>) options;
    }

    public boolean login() throws LoginException {
        String user = null;
        try {
            Callback[] callbacks = new Callback[2];
            callbacks[0] = new NameCallback("Username: ");
            callbacks[1] = new PasswordCallback("Password: ", false);
            try {
                callbackHandler.handle(callbacks);
            } catch (IOException ioe) {
                throw new LoginException(ioe.getMessage());
            } catch (UnsupportedCallbackException uce) {
                throw new LoginException(uce.getMessage() + " not available to obtain information from user");
            }

            user = ((NameCallback) callbacks[0]).getName();
            if (user == null)
                throw new FailedLoginException("user name is null");

            this.name = user;

            char[] tmpPassword = ((PasswordCallback) callbacks[1]).getPassword();
            if (tmpPassword == null) {
                tmpPassword = new char[0];
            }

            this.password = new String( tmpPassword );

            // verify the username/password
            boolean usernameCorrect = false;
            if (name.equals(USER1) || name.equals(USER2)) {
                usernameCorrect = true;
            }
            if (usernameCorrect && (password.equals(PASSWORD1))) {
                succeeded = true;
                return true;
            } else {
                succeeded = false;
                name = null;
                password = null;
                if (!usernameCorrect) {
                    throw new FailedLoginException("User Name Incorrect");
                } else {
                    throw new FailedLoginException("Password Incorrect");
                }
            }
        } catch (LoginException ex) {
            LOGGER.info("Login failed {}", user, ex);
            throw ex;
        }
    }

    public boolean commit() throws LoginException {
        boolean commit = false;
        if (succeeded == false) {
            return commit;
        } else {
            commit = true;
            // First we authenticate the user
            principal = new TestPrincipal(name);
            commit &= subject.getPrincipals().add(principal);

            // Then we try to authorize him with roles
            String rolesAsString = "";
            if (USER1.equals(this.name)) {
                rolesAsString = ROLES1;
            } else if (USER2.equals(this.name)) {
                rolesAsString = ROLES2;
            }

            String[] roles = rolesAsString.split(",\\s*");
            for (String roleName : roles) {
                Principal role = new TestGroup(roleName);
                LOGGER.info("adding role {}", roleName);
                commit &= subject.getPrincipals().add(role);
            }

            LOGGER.info("user {} was assigned roles: {}", name, rolesAsString);
        }

        //clean state
        succeeded = false;
        name = null;
        password = null;

        return commit;
    }

    public boolean abort() throws LoginException {
        //not needed for test purposes
        return false;
    }

    public boolean logout() throws LoginException {
        if (subject != null) {
            if (principal != null) {
                subject.getPrincipals().remove(principal);
            }
        }
        succeeded = false;
        name = null;
        password = null;
        return true;
    }
}