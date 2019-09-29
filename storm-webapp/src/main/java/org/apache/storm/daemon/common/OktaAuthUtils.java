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

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

public class OktaAuthUtils {

    private static final String COOKIE_NAME_OKTA_AT = "okta_at";
    private static final String COOKIE_NAME_OKTA_IT = "okta_it";

    /**
     * getOKTAAccessToken.
     * @param request servlet request
     * @return cookie if present
     */
    @SuppressWarnings("checkstyle:AbbreviationAsWordInName")
    public static String getOKTAAccessToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(COOKIE_NAME_OKTA_AT)) {
                    return cookie.getValue();
                }
                if (cookie.getName().equals(COOKIE_NAME_OKTA_IT)) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
