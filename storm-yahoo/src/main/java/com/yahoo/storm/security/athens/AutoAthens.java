package com.yahoo.storm.security.athens;

import org.apache.storm.security.auth.IAutoCredentials;

import java.util.List;
import java.util.Map;
import javax.security.auth.Subject;

import com.yahoo.auth.zts.ZTSClient;
import com.yahoo.auth.zts.ZTSClientTokenCacher;
import org.apache.storm.Config;
import org.apache.storm.metric.api.IMetricsRegistrant;
import org.apache.storm.task.TopologyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automatically push Athens RoleTokens to worker processes.
 */
public class AutoAthens implements IAutoCredentials, IMetricsRegistrant {
    public static final String DEFAULT_TENANT_DOMAIN_CONF = "yahoo.athens.tenant.domain";
    public static final String DEFAULT_TENANT_SERVICE_CONF = "yahoo.athens.tenant.service";
    public static final String ROLES_CONF = "yahoo.athens.roles";
    public static final String TENANT_DOMAIN = "tenant-domain";
    public static final String TENANT_SERVICE = "tenant-service";
    public static final String ROLE = "role";
    public static final String ROLE_SUFFIX = "suffix";
    public static final String TRUST_DOMAIN = "trust-domain";
    private static final int ONE_DAY_SECS = 24 * 60 * 60;
    private static final String ATHENS_TOKEN_PREFIX = "yahoo-athens_token_";
    private static final String ATHENS_ROLE_PREFIX = "yahoo-athens_role_";
    private static final String ATHENS_TRUST_PREFIX = "yahoo-athens_trust_";
    private static final String ATHENS_EXPIRATION_PREFIX = "yahoo-athens_expiration_";
    private static final Logger LOG = LoggerFactory.getLogger(AutoAthens.class);
    private Map conf;
    Map<String, String> credentials;

    @Override
    public void prepare(Map conf) {
        this.conf = conf;
    }

    private static String asTokenKey(int num) {
        return ATHENS_TOKEN_PREFIX + num;
    }

    private static String asTokenExpirationKey(int num) {
        return ATHENS_EXPIRATION_PREFIX + num;
    }

    private static String asRoleKey(int num) {
        return ATHENS_ROLE_PREFIX + num;
    }

    private static String asTrustKey(int num) {
        return ATHENS_TRUST_PREFIX + num;
    }

    private static void populateCredentials(Map<String, String> credentials,
                                            final int confNum,
                                            final String defaultTenantDomain,
                                            final String defaultTenantService,
                                            final String tenantDomain,
                                            final String tenantService,
                                            final String roleDomain,
                                            final String roleSuffix,
                                            final String trustDomain) {
        if (tenantDomain == null && defaultTenantDomain == null) {
            throw new RuntimeException("You must set either " + DEFAULT_TENANT_DOMAIN_CONF + " or include a " + TENANT_DOMAIN + " key as part of " + ROLES_CONF);
        }
        String td = tenantDomain == null ? defaultTenantDomain : tenantDomain;

        if (tenantService == null && defaultTenantService == null) {
            throw new RuntimeException("You must set either " + DEFAULT_TENANT_SERVICE_CONF + " or include a " + TENANT_SERVICE + " key as part of " + ROLES_CONF);
        }
        String ts = tenantService == null ? defaultTenantService : tenantService;

        if (roleDomain == null) {
           throw new RuntimeException("If a role is provided as a Map, the roleDomain must be under the " + ROLE + " key");
        }

        try (ZTSClient client = new ZTSClient(td, ts)) {
            Long expirationTime = System.currentTimeMillis() + ONE_DAY_SECS * 1000L;
            String roleToken = client.getRoleToken(roleDomain, roleSuffix, trustDomain, ONE_DAY_SECS - 1, ONE_DAY_SECS, false).getToken();
            LOG.info("Fetched Athens RoleToken {}", roleToken);
            credentials.put(asTokenKey(confNum), roleToken);
            credentials.put(asTokenExpirationKey(confNum), expirationTime.toString());
            if (roleSuffix != null) {
                credentials.put(asRoleKey(confNum), roleSuffix);
            }
            if (trustDomain != null) {
                credentials.put(asTrustKey(confNum), trustDomain);
            }
        }
    }

    private static void populateCredentials(Map<String, String> credentials,
                                            final int confNum,
                                            final String defaultTenantDomain,
                                            final String defaultTenantService,
                                            final Object role) {
        if (role instanceof String) {
            populateCredentials(credentials, confNum, defaultTenantDomain, defaultTenantService, null, null, (String)role, null, null);
        } else if (role instanceof Map) {
            Map r = (Map)role;
            populateCredentials(credentials, confNum, defaultTenantDomain, defaultTenantService, (String)r.get(TENANT_DOMAIN),
                                (String)r.get(TENANT_SERVICE), (String)r.get(ROLE), (String)r.get(ROLE_SUFFIX), (String)r.get(TRUST_DOMAIN));
        } else {
            throw new RuntimeException("A role must either be a String, or a Map of parameters");
        }
    }
 
    @Override
    public void populateCredentials(Map<String, String> credentials) {
        this.credentials = credentials;
        int confNum = 1;
        String defaultTenantDomain = (String)conf.get(DEFAULT_TENANT_DOMAIN_CONF);
        String defaultTenantService = (String)conf.get(DEFAULT_TENANT_SERVICE_CONF);
        Object roles = conf.get(ROLES_CONF);
        if (roles instanceof String || roles instanceof Map) {
            populateCredentials(credentials, confNum, defaultTenantDomain, defaultTenantService, roles);
            confNum++;
        } else if (roles instanceof List) {
            for (Object o: (List)roles) {
                populateCredentials(credentials, confNum, defaultTenantDomain, defaultTenantService, o);
                confNum++;
            }
        } else if (roles != null) {
            throw new RuntimeException(ROLES_CONF + " must be set to a String "
                    + "role, a Map or parameters, or a list of String/Maps");
        }
    }

    @Override
    public void updateSubject(Subject subject, Map<String, String> credentials) {
        this.credentials = credentials;
        populateSubject(subject, credentials);
    }

    private static boolean isAthensTokenKey(String key) {
        return key.startsWith(ATHENS_TOKEN_PREFIX);
    }

    private static int getAthensTokenKeyNum(String key) {
        return Integer.valueOf(key.substring(ATHENS_TOKEN_PREFIX.length()));
    }

    @Override
    public void populateSubject(Subject subject, Map<String, String> credentials) {
        if (credentials != null) {
            this.credentials = credentials;
            for (Map.Entry<String, String> entry: credentials.entrySet()) {
                String key = entry.getKey();
                if (isAthensTokenKey(key)) {
                    int num = getAthensTokenKeyNum(key);
                    String roleName = credentials.get(asRoleKey(num)); //can be null
                    String trustDomain = credentials.get(asTrustKey(num)); //can be null
                    ZTSClientTokenCacher.setRoleToken(entry.getValue(), roleName, trustDomain);
                }
            }
        }
    }

    private static boolean isAthensExpirationKey(String key) {
        return key.startsWith(ATHENS_EXPIRATION_PREFIX);
    }

    private Long getMsecsUntilExpiration() {
        if (this.credentials == null) {
            return null;
        }
        Long shortestExpirationTime = null;
        for (Map.Entry<String, String> entry: credentials.entrySet()) {
            if (isAthensExpirationKey(entry.getKey())) {
                String expirationString = entry.getValue();
                Long expirationTime = Long.parseLong(expirationString);
                if (shortestExpirationTime == null || expirationTime < shortestExpirationTime) {
                    shortestExpirationTime = expirationTime;
                }
            }
        }
        if (shortestExpirationTime != null) {
            return shortestExpirationTime - System.currentTimeMillis();
        }
        return null;
    }

    @Override
    public void registerMetrics(TopologyContext topoContext, Map<String, Object> topoConf) {
        int bucketSize = ((Number) topoConf.get(Config.TOPOLOGY_BUILTIN_METRICS_BUCKET_SIZE_SECS)).intValue();
        topoContext.registerMetric("Athens-TimeToExpiryMsecs", () -> getMsecsUntilExpiration(), bucketSize);
    }

    public static void main(String[] args) throws Exception {
        AutoAthens athens = new AutoAthens();
        athens.prepare(org.apache.storm.utils.Utils.readStormConfig());
        java.util.HashMap<String, String> creds = new java.util.HashMap<String, String>();
        athens.populateCredentials(creds);
        LOG.info("Creds {}", creds);
    }
}
