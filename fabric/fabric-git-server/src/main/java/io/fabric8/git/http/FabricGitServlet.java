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
package io.fabric8.git.http;

import io.fabric8.git.GitService;
import io.fabric8.zookeeper.ZkPath;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.shared.SharedCount;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.Ref;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("serial")
public class FabricGitServlet extends GitServlet {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricGitServlet.class);

    private final CuratorFramework curator;
    private SharedCount counter;
    private Git git;

    FabricGitServlet(Git git, CuratorFramework curator) {
        this.curator = curator;
        this.git = git;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        try {
            counter = new SharedCount(curator, ZkPath.GIT_TRIGGER.getPath(), 0);
            counter.start();

            Thread currentThread = Thread.currentThread();
            ClassLoader backupClassLoader = null;
            try {
                backupClassLoader = currentThread.getContextClassLoader();
                currentThread.setContextClassLoader(GitService.class.getClassLoader());
                Class.forName("org.eclipse.jgit.lib.BatchingProgressMonitor");
            } finally {
                currentThread.setContextClassLoader(backupClassLoader);
            }
        } catch (Exception ex) {
            handleException(ex);
        }
    }

    protected void handleException(Exception ex) throws ServletException {
        if(ex instanceof IllegalStateException && "Client is not started".equals(ex.getMessage())){
            LOGGER.debug("", ex);
            throw new ServletException("Error starting SharedCount. ZK Client is not Started");
        }else{
            LOGGER.error("Error starting SharedCount", ex);
            throw new ServletException("Error starting SharedCount", ex);
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            counter.close();
        } catch (IOException ex) {
            LOGGER.warn("Error closing SharedCount due to: " + ex + ". This exception is ignored.");
        }
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        /*
         * `git push` uses two exchanges in smart http protocol:
         * 1) preparing for push - this checks current state of branches on remote side
         *   > GET /jgit/_test-repo/info/refs?service=git-receive-pack HTTP/1.1
         *
         *   < Content-Type: application/x-git-receive-pack-advertisement
         * 2) actual push of crafted PACK file with changes to references being pushed
         *   > POST /jgit/_test-repo/git-receive-pack HTTP/1.1
         *   > Content-Type: application/x-git-receive-pack-request
         *
         *   < Content-Type: application/x-git-receive-pack-result
         */

        LOGGER.info("GG: GitHttp service req={}", req.getRequestURI());
        super.service(req, res);
        LOGGER.info("GG: GitHttp service req={}, res={}", req.getRequestURI(), res);

        // Ignore unwanted service requests
        String resContentType = res.getContentType();
        if (resContentType.contains("x-git-receive-pack-result")) {
            LOGGER.info("GitHttp service req={}, res={}", req.getRequestURI(), res);

            int httpStatus = 0;
            try {
                Method method = res.getClass().getMethod("getStatus");
                httpStatus = (Integer) method.invoke(res);
            } catch (Exception ex) {
                LOGGER.error("Cannot obtain http response code: " + ex);
            }

            if (httpStatus == HttpServletResponse.SC_OK) {
                if (LOGGER.isDebugEnabled()) {
                    try {
                        List<Ref> refs = git.branchList().call();
                        LOGGER.debug("Remote git content updated: {}", refs);
                    } catch (Exception ignored) {
                    }
                }
                try {
                    LOGGER.info("GG: Trying to set shared count to " + (counter.getCount() + 1));
                    while (!counter.trySetCount(counter.getCount() + 1)) ;
                    LOGGER.info("GG: Successfully set shared count to " + (counter.getCount()));
                } catch (Exception ex) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Error incrementing shared counter: " + ex + ". This exception is ignored.", ex);
                    }
                    LOGGER.warn("Error incrementing shared counter: " + ex + ". This exception is ignored.");
                }
            }
        }
    }

}
