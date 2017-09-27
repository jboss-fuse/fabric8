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
package io.fabric8.insight.elasticsearch.auth.plugin;

import org.elasticsearch.common.Base64;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.http.HttpChannel;
import org.elasticsearch.http.HttpRequest;
import org.elasticsearch.http.HttpServer;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.node.service.NodeService;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest.Method;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AccountException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Arrays;

import static org.elasticsearch.rest.RestStatus.OK;
import static org.elasticsearch.rest.RestStatus.UNAUTHORIZED;

public class HttpBasicServer extends HttpServer {

    private static final String HEADER_WWW_AUTHENTICATE = "WWW-Authenticate";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String AUTHENTICATION_SCHEME_BASIC = "Basic";

    private String realm;
    private String[] roles;
    private final boolean log;

    @Inject
    public HttpBasicServer(Settings settings, Environment environment, HttpServerTransport transport,
            RestController restController,
            NodeService nodeService) {
        super(settings, environment, transport, restController, nodeService);

        this.realm = settings.get("http.basic.realm", "karaf");
        this.roles = settings.getAsArray("http.basic.roles", new String[]{"admin", "manager", "viewer", "Monitor", "Operator", "Maintainer", "Deployer", "Auditor", "Administrator", "SuperUser"});
        this.log = settings.getAsBoolean("http.basic.log", false);
        Loggers.getLogger(getClass()).info("using realm: [{}] and authorized roles: [{}]",
                realm, roles);
    }

    @Override
    public void internalDispatchRequest(final HttpRequest request, final HttpChannel channel) {
        if (log) {
          logRequest(request);
        }
        // allow health check even without authorization
        if (healthCheck(request)) {
            channel.sendResponse(new BytesRestResponse(OK, "{\"OK\":{}}"));
        } else if (authorized(request)) {
            super.internalDispatchRequest(request, channel);
        } else {
          logUnAuthorizedRequest(request);
          BytesRestResponse response = new BytesRestResponse(UNAUTHORIZED, "Authentication Required");
          response.addHeader(HEADER_WWW_AUTHENTICATE, "Basic realm=\"" + this.realm + "\"");
          channel.sendResponse(response);
        }
    }

    private boolean healthCheck(final HttpRequest request) {
        String path = request.path();
        return (request.method() == Method.GET) && path.equals("/");
    }

    private boolean authorized(final HttpRequest request) {
      return allowOptionsForCORS(request) || authBasic(request);
    }

    public String getDecodedAuthHeader(HttpRequest request) {
        String authHeader = request.header(HEADER_AUTHORIZATION);
        if (authHeader != null && authHeader.length() > 0) {

            // Get the authType (Basic, Digest) and authInfo (user/password)
            // from the header
            authHeader = authHeader.trim();
            int blank = authHeader.indexOf(' ');
            if (blank > 0) {
                String authType = authHeader.substring(0, blank);
                String authInfo = authHeader.substring(blank).trim();

                // Check whether authorization type matches
                if (authType.equalsIgnoreCase(AUTHENTICATION_SCHEME_BASIC)) {
                    try {
                        return new String(Base64.decode(authInfo));
                    } catch (IOException e) {
                        logger.debug("Http Basic credentials: [{}] impossible to base 64 decode.", authInfo);
                        return "";
                    }
                } else {
                    logger.debug("Http Basic authentication Schema: [{}] not supported only [{}] is supported.", authType, AUTHENTICATION_SCHEME_BASIC);
                    return "";
                }
            } else {
                logger.debug("Http Basic authentication Header: [{}] impossible to base 64 decode.", authHeader);
                return "";
            }
        } else {
            logger.debug("Http Basic authentication Header: [{}] not  present.", HEADER_AUTHORIZATION);
            return "";
        }
    }

    private boolean authBasic(final HttpRequest request) {
        String decodedAuthHeader = getDecodedAuthHeader(request);
        if (!decodedAuthHeader.isEmpty()) {
            String[] userAndPassword = decodedAuthHeader.split(":", 2);
            String givenUser = userAndPassword[0];
            String givenPass = userAndPassword[1];

            // authenticate
            Subject subject = doAuthenticate(givenUser, givenPass);
            if (subject != null) {
                // succeed
                return true;
            }
        }
        //failed
        return false;
    }

    private Subject doAuthenticate(final String username, final String password) {
        try {
            Subject subject = new Subject();
            LoginContext loginContext = new LoginContext(realm, subject, new CallbackHandler() {
                public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
                    for (int i = 0; i < callbacks.length; i++) {
                        if (callbacks[i] instanceof NameCallback) {
                            ((NameCallback) callbacks[i]).setName(username);
                        } else if (callbacks[i] instanceof PasswordCallback) {
                            ((PasswordCallback) callbacks[i]).setPassword(password.toCharArray());
                        } else {
                            throw new UnsupportedCallbackException(callbacks[i]);
                        }
                    }
                }
            });
            loginContext.login();
            logger.debug("Login successful: {}", subject.toString());
            boolean found = false;
            for (String role : roles) {
                if (role != null && role.length() > 0 && !found) {
                    String roleName = role.trim();
                    int idx = roleName.indexOf(':');
                    if (idx > 0) {
                        roleName = roleName.substring(idx + 1);
                    }

                    for (Principal p : subject.getPrincipals()) {
                        logger.debug("Principal found in real: {}", p.getName() );
                        if (p.getName().equals(roleName)) {
                            found = true;
                            break;
                        }
                    }

                }
            }
            if (!found) {
                throw new FailedLoginException("User does not have the required role " + Arrays.asList(roles));
            }

            return subject;
        } catch (AccountException e) {
            logger.warn("Account failure {}", e.getMessage());
            return null;
        } catch (LoginException e) {
            logger.debug("Login failed {}", e.getMessage());
            return null;
        }
    }

    private boolean allowOptionsForCORS(HttpRequest request) {
        // https://en.wikipedia.org/wiki/Cross-origin_resource_sharing the
        // specification mandates that browsers “preflight” the request, soliciting
        // supported methods from the server with an HTTP OPTIONS request
        // in elasticsearch.yml set
        // http.cors.allow-headers: "X-Requested-With, Content-Type, Content-Length, Authorization"
        if (request.method() == Method.OPTIONS) {
            logger.debug("CORS type {}, address {}, path {}, request {}, content {}",
                    request.method(), getAddress(request), request.path(), request.params(), request.content().toUtf8());
            return true;
        }
        return false;
    }

    private void logRequest(final HttpRequest request) {
      String addr = getAddress(request).getHostAddress();
      String t = "Authorization:{}, Host:{}, Path:{}, Request-IP:{}, " +
        "Client-IP:{}, X-Client-IP{}";
      logger.info(t,
                  request.header("Authorization"),
                  request.header("Host"),
                  request.path(),
                  addr,
                  request.header("X-Client-IP"),
                  request.header("Client-IP"));
    }

    private void logUnAuthorizedRequest(final HttpRequest request) {
        String addr = getAddress(request).getHostAddress();
        String t = "UNAUTHORIZED type:{}, address:{}, path:{}, request:{},"
          + "content:{}, credentials:{}";
        logger.warn(t,
                request.method(), addr, request.path(), request.params(),
                request.content().toUtf8(), getDecodedAuthHeader(request));
    }

    private InetAddress getAddress(HttpRequest request) {
        return ((InetSocketAddress) request.getRemoteAddress()).getAddress();
    }

}
