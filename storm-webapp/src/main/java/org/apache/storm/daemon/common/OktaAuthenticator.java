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

import com.google.common.base.Splitter;
import com.oath.okta.client.v1.keys.KeysService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SigningKeyResolverAdapter;

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
import java.util.List;
import java.util.Map;
import javax.naming.AuthenticationException;
import javax.servlet.FilterConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.storm.utils.ConfigUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class OktaAuthenticator {

    public static final Logger LOG = LoggerFactory.getLogger(OktaAuthenticator.class);

    private static final String OKTA_HTTPS_KEYSTORE_PATH = "okta.https.keystore.path";
    private static final String OKTA_HTTPS_KEYSTORE_KEY = "okta.https.keystore.key";
    private static final String OKTA_APP_ISSUER = "okta.app.issuer";
    private static final String OKTA_APP_PUBLIC_KEYS_URI = "okta.app.public.keys.uri";
    private static final String OKTA_APP_CLIENT_IDS = "okta.app.client.ids";

    Map<String, Object> conf;

    private static final String CLIENT_ID = "aud";

    private String oktaAppIssuer;
    private List<String> oktaAppClientIds;
    private OktaJwtsSigningKeyResolver jwtsSigningKeyResolver;

    /**
     * Initializes OktaAuthenticator.OktaJwtsSigningKeyResolver
     * @param filterConfig filterConfig from Jersey server
     */
    public OktaAuthenticator(FilterConfig filterConfig) {
        conf = ConfigUtils.readStormConfig();
        oktaAppClientIds =
                Splitter.on(',').omitEmptyStrings().trimResults().splitToList(
                        filterConfig.getInitParameter(OKTA_APP_CLIENT_IDS)
                );
        oktaAppIssuer = filterConfig.getInitParameter(OKTA_APP_ISSUER);
        jwtsSigningKeyResolver = new OktaJwtsSigningKeyResolver(filterConfig);
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
     */
    public boolean authenticate(ServletRequest servletRequest,
                             ServletResponse servletResponse) {
        final HttpServletResponse response = (HttpServletResponse) servletResponse;
        final HttpServletRequest request = (HttpServletRequest) servletRequest;

        try {
            String accessToken = OktaAuthUtils.getOKTAAccessToken(request);
            if (accessToken == null) {
                return false;
            }

            Jws<Claims> jws = Jwts.parser()
                    .setSigningKeyResolver(jwtsSigningKeyResolver)
                    .parseClaimsJws(accessToken);
            Claims claims = jws.getBody();
            String clientId = (String) claims.get(CLIENT_ID);
            String issuer = claims.getIssuer();

            if (!issuer.equals(oktaAppIssuer)) {
                throw new AuthenticationException("Invalid okta issuer: " + issuer);
            }

            if (!oktaAppClientIds.contains(clientId)) {
                throw new AuthenticationException("Invalid client id: " + clientId);
            }

            return true;
        } catch (Exception ex) {
            LOG.debug("Failed to validate oauth2 token", ex);
            return false;
        }

    }

    private static class OktaJwtsSigningKeyResolver extends SigningKeyResolverAdapter {
        private File keyStoreFile;
        private String keyStorePassword;
        private KeyStore keyStore;
        private KeysService keyService;
        private boolean reloadKeyStore = true;

        public OktaJwtsSigningKeyResolver(FilterConfig filterConfig) {
            keyStoreFile = new File(filterConfig.getInitParameter(OKTA_HTTPS_KEYSTORE_PATH));
            keyStorePassword = filterConfig.getInitParameter(OKTA_HTTPS_KEYSTORE_KEY);
            keyService = new KeysService(
                    filterConfig.getInitParameter(OKTA_APP_PUBLIC_KEYS_URI)
            );
        }

        private Key resolveSigningKey(JwsHeader jwsHeader) throws Exception {
            Key publicKey = keyService.getKey(jwsHeader.getKeyId()).toSecurityKey();
            if (publicKey == null) {
                if (keyStore == null) {
                    loadKeyStore();
                }
                if (keyStore != null) {
                    publicKey = getOktaServerPublicKeyFromKeyStore(jwsHeader.getKeyId());
                } else {
                    LOG.error("Unable to find public key for kid: {}", jwsHeader.getKeyId());
                }
            }
            return publicKey;
        }

        @Override
        public Key resolveSigningKey(JwsHeader jwsHeader, Claims claims) {
            try {
                return resolveSigningKey(jwsHeader);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
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
            }

            // incase where the keystore got the new version of keys, we need to reload the keystore
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

        private boolean loadKeyStore()
                throws KeyStoreException, IOException,
                CertificateException, NoSuchAlgorithmException {
            if (keyStoreFile != null && keyStorePassword != null) {
                LOG.debug(
                        "Loading OKTA public keys from keystore {}", keyStoreFile.getAbsolutePath()
                );
                FileInputStream is = new FileInputStream(keyStoreFile);
                keyStore = KeyStore.getInstance(keyStoreFile.getAbsolutePath().endsWith(".p12") ? "PKCS12" : "jks");
                keyStore.load(is, keyStorePassword.toCharArray());
                return true;
            }
            return false;
        }
    }
}
