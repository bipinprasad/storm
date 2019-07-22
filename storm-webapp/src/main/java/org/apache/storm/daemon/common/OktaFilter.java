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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.okta.jwt.AccessTokenVerifier;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;

import com.okta.jwt.JwtVerifiers;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.impl.DefaultJwsHeader;
import io.jsonwebtoken.impl.TextCodec;
import io.jsonwebtoken.lang.Strings;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Map;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.storm.DaemonConfig;
import org.apache.storm.utils.ConfigUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class OktaFilter implements Filter {

    public static final Logger LOG = LoggerFactory.getLogger(OktaFilter.class);

    // Cookie name ref: https://git.ouroath.com/CorporateIdentity/okta_sso_java/blob/63b6b23e106ec20a08c371136776d6cb439d27db/okta_sso_java_example_server/src/main/java/com/oath/okta/sso/webapp/OktaTestServlet.java#L86
    private static final String COOKIE_NAME_OKTA_AT = "okta_at";
    private static final String COOKIE_NAME_OKTA_IT = "okta_it";
    private static final String SUBJECT = "sub";
    private static final String CLIENT_ID = "cid";

    private File keyStoreFile;
    private String keyStorePassword;
    private String oktaAppIssuer;
    private String oktaAppAudience;
    private String oktaAppClientId;
    private String oktaUrl;
    private boolean reloadKeyStore = true;
    private KeyStore keyStore;
    private AccessTokenVerifier jwtVerifier;

    Map<String, Object> conf;


    private void initJwtVerifier() {
        if (oktaAppIssuer != null && oktaAppAudience != null) {
            LOG.debug("Setting up fetching of OKTA public keys from " + oktaAppIssuer);
            jwtVerifier = JwtVerifiers.accessTokenVerifierBuilder()
                    .setIssuer(oktaAppIssuer)
                    .setAudience(oktaAppAudience)
                    .setConnectionTimeout(Duration.ofSeconds(1))
                    .setReadTimeout(Duration.ofSeconds(1))
                    .build();
        } else {
            throw new IllegalStateException(
                    "KeyStore/Okta App parameters missing for Okta Authentication"
            );
        }
    }


    private PublicKey getOktaServerPublicKeyFromKeyStore(String keyId) throws Exception {
        Key key = keyStore.getKey(keyId, keyStorePassword.toCharArray());
        PublicKey oktaServerPublicKey = null;
        if (key instanceof PrivateKey) {
            // Get certificate of public key
            Certificate cert = keyStore.getCertificate(keyId);
            // Get public key
            oktaServerPublicKey = cert.getPublicKey();
            jwtVerifier = null;
        }
        if (oktaServerPublicKey == null) {
            if (reloadKeyStore) {
                reloadKeyStore = false;
                loadKeyStore();
                getOktaServerPublicKeyFromKeyStore(keyId);
            } else {
                LOG.warn("Unable to retrieve okta server public key after keystore reload");
                return null;
            }
        } else {
            reloadKeyStore = true;
            return oktaServerPublicKey;
        }
        return null;
    }


    private String getOktaAccessToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                LOG.debug(cookie.getName());
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


    private Map<String, Object> readValue(String val) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return (Map) objectMapper.readValue(val, Map.class);
        } catch (IOException e) {
            throw new MalformedJwtException("Unable to read JSON value: " + val, e);
        }
    }


    private String getKeyIdFromJwt(String jwt) {
        String base64UrlEncodedHeader = null;
        DefaultJwsHeader header;
        StringBuilder sb = new StringBuilder(128);
        char[] jwtArray = jwt.toCharArray();
        for (int i = 0; i < jwtArray.length; ++i) {
            char c = jwtArray[i];
            if (c == '.') {
                CharSequence tokenSeq = Strings.clean(sb);
                base64UrlEncodedHeader = tokenSeq != null ? tokenSeq.toString() : null;
                break;
            } else {
                sb.append(c);
            }
        }
        if (base64UrlEncodedHeader != null) {
            String payload = TextCodec.BASE64URL.decodeToString(base64UrlEncodedHeader);
            Map<String, Object> m = readValue(payload);
            header = new DefaultJwsHeader(m);
            return header.getKeyId();
        }
        return null;
    }

    private boolean loadKeyStore()
            throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException {
        if (keyStore != null && keyStorePassword != null) {
            LOG.debug("Loading OKTA public keys from keystore" + keyStoreFile.getAbsolutePath());
            FileInputStream is = new FileInputStream(keyStoreFile);
            keyStore = KeyStore.getInstance(
                    keyStoreFile.getAbsolutePath().endsWith(".p12") ? "PKCS12" : "jks"
            );
            keyStore.load(is, keyStorePassword.toCharArray());
            return true;
        }
        return false;
    }


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
     * @param filterConfig servlet filter config
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        conf = ConfigUtils.readStormConfig();
        keyStoreFile = new File((String) conf.get(DaemonConfig.OKTA_HTTPS_KEYSTORE_PATH));
        keyStorePassword = (String) conf.get(DaemonConfig.OKTA_HTTPS_KEYSTORE_KEY);
        oktaAppIssuer = (String) conf.get(DaemonConfig.OKTA_APP_ISSUER);
        oktaAppAudience = (String) conf.get(DaemonConfig.OKTA_APP_AUDIENCE);
        oktaAppClientId = (String) conf.get(DaemonConfig.OKTA_APP_CLIENT_ID);
        oktaUrl = (String) conf.get(DaemonConfig.OKTA_URL);
        try {
            if (!loadKeyStore()) {
                initJwtVerifier();
            }
        } catch (Exception e) {
            throw new RuntimeException("Error while initializing Okta authentication", e);
        }
    }

    /**
     * The <code>doFilter</code> method of the Filter is called by the
     * container each time a request/response pair is passed through the
     * chain due to a client request for a resource at the end of the chain.
     * The FilterChain passed in to this method allows the Filter to pass
     * on the request and response to the next entity in the chain.
     *
     * <p>A typical implementation of this method would follow the following
     * pattern:
     * <ol>
     * <li>Examine the request
     * <li>Optionally wrap the request object with a custom implementation to
     * filter content or headers for input filtering
     * <li>Optionally wrap the response object with a custom implementation to
     * filter content or headers for output filtering
     * <li>
     * <ul>
     * <li><strong>Either</strong> invoke the next entity in the chain
     * using the FilterChain object
     * (<code>chain.doFilter()</code>),
     * <li><strong>or</strong> not pass on the request/response pair to
     * the next entity in the filter chain to
     * block the request processing
     * </ul>
     * <li>Directly set headers on the response after invocation of the
     * next entity in the filter chain.
     * </ol>
     *
     * @param servletRequest request
     * @param servletResponse response
     * @param filterChain rest of the filter chain
     */
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain filterChain) throws IOException, ServletException {
        final HttpServletResponse response = (HttpServletResponse) servletResponse;
        final HttpServletRequest request = (HttpServletRequest) servletRequest;

        String principal;
        String clientId;
        try {
            String accessToken = getOktaAccessToken(request);
            if (accessToken == null) {
                oktaRedirect(response);
                return;
            }
            if (jwtVerifier != null) {
                Jwt jwt = jwtVerifier.decode(accessToken);
                principal = (String) jwt.getClaims().get(SUBJECT);
                clientId = (String) jwt.getClaims().get(CLIENT_ID);
            } else {
                PublicKey oktaServerPublicKey =
                        getOktaServerPublicKeyFromKeyStore(getKeyIdFromJwt(accessToken));
                if (oktaServerPublicKey != null) {
                    Jws<Claims> jws =
                            Jwts.parser().setSigningKey(
                                    oktaServerPublicKey
                            ).parseClaimsJws(accessToken);
                    principal = jws.getBody().getSubject();
                    clientId = (String) jws.getBody().get(CLIENT_ID);
                } else {
                    throw new RuntimeException("No public key found for Okta Authentication");
                }
            }
            if (clientId != oktaAppClientId) {
                throw new ServletException("Invalid client id: " + clientId);
            }
            if (principal != null) {
                filterChain.doFilter(request, response);
            }
        } catch (ExpiredJwtException | JwtVerificationException e) {
            throw new ServletException("OKTA JWT token has expired: " + e.getMessage());
        } catch (Exception e) {
            LOG.error(e.getMessage());
            throw new ServletException(e.getMessage());
        }
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

    private void oktaRedirect(HttpServletResponse response)
            throws IOException {
        String redirectUrl = response.encodeRedirectURL(oktaUrl);
        LOG.debug("redirecting to url: " + redirectUrl);

        response.sendRedirect(redirectUrl);
    }

}
