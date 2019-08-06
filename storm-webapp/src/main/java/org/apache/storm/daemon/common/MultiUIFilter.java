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
import java.util.Objects;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import yjava.servlet.filter.BouncerFilter;

public class MultiUIFilter implements Filter {

    private AthenzAuthenticator athenzAuthenticator;
    private OktaAuthenticator oktaAuthenticator;
    private BouncerFilter yahooBouncerFilter;

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
        this.yahooBouncerFilter = new BouncerFilter();
        this.yahooBouncerFilter.init(filterConfig);
    }

    /**
     * Currently we check for Athenz and then Okta. TODO: Fallback to bouncer if none work
     *
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        boolean allowThrough = false;
        if (
                this.athenzAuthenticator.authenticate(
                        (HttpServletRequest) request,
                        (HttpServletResponse) response
                ) || this.oktaAuthenticator.authenticate(request, response)
        ) {
            allowThrough = true;
        } else {
            yahooBouncerFilter.doFilter(request, response, noOpFilterChain);
            if (Objects.equals(request.getAttribute("bouncer.bypassthru"), Integer.valueOf(1))) {
                allowThrough = true;
            }
        }

        if (allowThrough) {
            chain.doFilter(request, response);
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
