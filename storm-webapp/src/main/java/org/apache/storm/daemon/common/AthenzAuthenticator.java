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

import com.oath.okta.client.v1.keys.KeysService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SigningKeyResolverAdapter;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import javax.servlet.FilterConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AthenzAuthenticator {

    public static final Logger LOG = LoggerFactory.getLogger(AthenzAuthenticator.class);

    private static final String X509_ATTRIBUTE = "javax.servlet.request.X509Certificate";
    private static final String USER_PREFIX = "user.";
    private static final String ROLE = "role.";

    private static final String ATHENZ_ROLE_PREFIX = "athenz.auth.role.prefix";
    private static final String ATHENZ_DOMAIN = "athenz.auth.domain";
    private static final String ATHENZ_TRUST_STORE = "athenz.auth.truststore";
    private static final String ATHENZ_TRUST_STORE_PASSWORD = "athenz.auth.truststore.password";
    private static final String ATHENZ_ISSUER = "athenz.auth.issuer";
    private static final String ATHENZ_URI = "athenz.auth.uri";

    private AthenzJwtsSigningKeyResolver jwtsSigningKeyResolver;
    private String athenzIssuer;
    private String athenzAudience;
    private String domainRolePrefix;
    private String rolePrefix;
    private static Set<String> trustedX509Issuers = new HashSet<>();

    /**
     * AthenzAuthenticator constructor.
     * @param filterConfig filterConfig from Jersey
     */
    public AthenzAuthenticator(FilterConfig filterConfig) {
        String configAthenzRolePrefix = filterConfig.getInitParameter(ATHENZ_ROLE_PREFIX);
        domainRolePrefix = filterConfig.getInitParameter(ATHENZ_DOMAIN)
                + ":" + configAthenzRolePrefix + ".";
        rolePrefix = configAthenzRolePrefix.replaceFirst(ROLE, "") + ".";
        setX509CAIssuers(
                filterConfig.getInitParameter(ATHENZ_TRUST_STORE),
                filterConfig.getInitParameter(ATHENZ_TRUST_STORE_PASSWORD)
        );
        jwtsSigningKeyResolver = new AthenzJwtsSigningKeyResolver(filterConfig);
        athenzIssuer = filterConfig.getInitParameter(ATHENZ_ISSUER);
        // audience and domain are same in case of Athenz
        athenzAudience = filterConfig.getInitParameter(ATHENZ_DOMAIN);
    }

    /**
     * Adapted from Presto Athenz authenticator.
     * @param request servletRequest
     * @param response servletResponse
     * @return true if to be passed through or false if otherwise
     */
    public boolean authenticate(HttpServletRequest request, HttpServletResponse response) {
        try {
            X509Certificate[] certs = (X509Certificate[]) request.getAttribute(X509_ATTRIBUTE);
            if ((certs == null) || (certs.length == 0)) {
                return false;
            }

            if (trustedX509Issuers.contains(certs[0].getIssuerX500Principal().getName())) {
                X500Principal principal = certs[0].getSubjectX500Principal();
                LdapName ldapName = new LdapName(principal.getName());
                List<Rdn> rdns = ldapName.getRdns();
                for (Rdn rdn : rdns) {
                    if (rdn.getType().equalsIgnoreCase("cn")) {
                        String cn = rdn.getValue().toString();
                        if (isAthenzPrincipalInCn(cn)) {
                            String accessToken = OktaAuthUtils.getOKTAAccessToken(request);
                            if (accessToken != null) {
                                Jws<Claims> jws = Jwts.parser()
                                        .setSigningKeyResolver(jwtsSigningKeyResolver)
                                        .parseClaimsJws(accessToken);
                                Claims claims = jws.getBody();
                                String audience = claims.getAudience();
                                String issuer = claims.getIssuer();
                                String subject = claims.getSubject();

                                if (!issuer.equals(athenzIssuer)) {
                                    LOG.info("Invalid athenz issuer: " + issuer);
                                    return false;
                                }

                                if (!audience.equals(athenzAudience)) {
                                    LOG.info("Invalid athenz audience: " + audience);
                                    return false;
                                }

                                // cn in mTLS and sub/uid/client_id in oauth2 token
                                // should refer to same service identity
                                // https://git.ouroath.com/pages/athens/athenz-guide/zts_oauth2_guide/
                                if (!cn.equals(subject)) {
                                    LOG.info(
                                            "The subject {} in Athenz oauth2 token does not match the CN {}"
                                            + " in the service cert used for mutual TLS".format(subject, cn)
                                    );
                                    return false;
                                }

                            }
                        }
                        return true;
                    }
                }
            }
        } catch (InvalidNameException e) {
            LOG.error(e.getMessage());
        }
        return false;
    }

    private boolean isAthenzPrincipalInCn(String principalOrRole) {
        if (principalOrRole.startsWith(USER_PREFIX)) {
            return true;
        }
        if (principalOrRole.startsWith(domainRolePrefix)) {
            return true;
        }
        return false;
    }

    private static class AthenzJwtsSigningKeyResolver
            extends SigningKeyResolverAdapter {
        private KeysService keyService;

        public AthenzJwtsSigningKeyResolver(FilterConfig filterConfig) {
            keyService = new KeysService(null);
            if (filterConfig.getInitParameter(ATHENZ_URI) != null) {
                keyService.setAthenzConfig(
                        filterConfig.getInitParameter(ATHENZ_URI) + "/oauth2/keys", null,
                        null, filterConfig.getInitParameter(ATHENZ_TRUST_STORE),
                        filterConfig.getInitParameter(ATHENZ_TRUST_STORE_PASSWORD)
                );
            }
        }

        private Key resolveSigningKey(JwsHeader jwsHeader) throws Exception {
            Key publicKey = keyService.getKey(jwsHeader.getKeyId()).toSecurityKey();
            if (publicKey == null) {
                LOG.error("Unable to find public key for kid: " + jwsHeader.getKeyId());
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
    }

    private final void setX509CAIssuers(final String issuersFileName,
                                        final String trustStorePassword) {
        if (issuersFileName == null || issuersFileName.isEmpty()) {
            return;
        }
        try {
            Path path = Paths.get(issuersFileName);
            if (!path.isAbsolute()) {
                path = Paths.get(getClass().getClassLoader().getResource(issuersFileName).toURI());
            }

            KeyStore ks = null;
            char[] password = trustStorePassword != null ? trustStorePassword.toCharArray() : null;
            try (InputStream in = new FileInputStream(path.toString())) {
                ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(in, password);
            }

            for (Enumeration<?> e = ks.aliases(); e.hasMoreElements();) {
                String alias = (String) e.nextElement();
                X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
                X500Principal issuerx500Principal = cert.getIssuerX500Principal();
                String issuer = issuerx500Principal.getName();
                trustedX509Issuers.add(issuer);
                LOG.debug("issuer: {} ", issuer);
            }
        } catch (Throwable e) {
            throw new RuntimeException(
                    "Unable to set trusted issuers from file: "
                            + issuersFileName, e
            );
        }
    }
}
