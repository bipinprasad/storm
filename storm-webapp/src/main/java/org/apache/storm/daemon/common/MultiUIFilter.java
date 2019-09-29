/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.storm.daemon.common;

import java.io.IOException;
import java.security.Principal;
import java.util.Objects;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yjava.servlet.YJavaHttpServletRequestWrapper;

@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
public class MultiUIFilter implements Filter {

    public static final Logger LOG = LoggerFactory.getLogger(MultiUIFilter.class);


    private AthenzAuthenticator athenzAuthenticator;
    private OktaAuthenticator oktaAuthenticator;

    /** No-op filter chain. */
    private final FilterChain noOpFilterChain = new FilterChain() {
        @Override
        public void doFilter(final ServletRequest request,
                             final ServletResponse response) {
            // do nothing
        }
    };

    /**
     * Called by the web container to indicate to a filter that it is
     * being placed into service.
     *
     * <p>The servlet container calls the init
     * method exactly once after instantiating the filter. The init
     * method must complete successfully before the filter is asked to do any
     * filtering work.
     *
     * <p>The web container cannot place the filter into service if the init
     * method either
     * <ol>
     * <li>Throws a ServletException
     * <li>Does not return within a time period defined by the web container
     * </ol>
     *
     * @param filterConfig filterConfig from Jersey
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.athenzAuthenticator = new AthenzAuthenticator(filterConfig);
        this.oktaAuthenticator = new OktaAuthenticator(filterConfig);
    }

    /**
     * Currently we check for Athenz and then Okta.
     *
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        Principal userPrincipal =  this.athenzAuthenticator.authenticate(
                (HttpServletRequest) request,
                (HttpServletResponse) response
        );

        if (userPrincipal == null) {
            userPrincipal = this.oktaAuthenticator.authenticate(request, response);
        }

        if (userPrincipal != null) {
            final YJavaHttpServletRequestWrapper requestWrapper =
                    YJavaHttpServletRequestWrapper.wrap((HttpServletRequest) request);
            LOG.debug("Auth succeeded, got principal " + userPrincipal.toString() + " " + userPrincipal.getName());
            requestWrapper.setRemoteUser(userPrincipal.getName());
            requestWrapper.setUserPrincipal(userPrincipal);
            chain.doFilter(requestWrapper, response);
            return;
        }

        ((HttpServletResponse) response).sendError(HttpServletResponse.SC_FORBIDDEN);
    }

    /**
     * Called by the web container to indicate to a filter that it is being
     * taken out of service.
     *
     * <p>This method is only called once all threads within the filter's
     * doFilter method have exited or after a timeout period has passed.
     * After the web container calls this method, it will not call the
     * doFilter method again on this instance of the filter.
     *
     * <p>This method gives the filter an opportunity to clean up any
     * resources that are being held (for example, memory, file handles,
     * threads) and make sure that any persistent state is synchronized
     * with the filter's current state in memory.
     */
    @Override
    public void destroy() {

    }
}
