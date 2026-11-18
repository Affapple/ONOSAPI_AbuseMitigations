package lu.uni.sdn.apigateway;

/**
 * Names and defaults of the GatewayManager component-config properties
 * ({@code cfg set lu.uni.sdn.apigateway.GatewayManager <name> <value>}).
 *
 * <p>All modules default to enabled (fail secure). The evaluation turns modules
 * off explicitly for config C2 ("ACL only").</p>
 */
public final class OsgiPropertyConstants {

    private OsgiPropertyConstants() {
    }

    public static final String ACL_ENABLED = "aclEnabled";
    public static final boolean ACL_ENABLED_DEFAULT = true;

    public static final String RATE_LIMIT_ENABLED = "rateLimitEnabled";
    public static final boolean RATE_LIMIT_ENABLED_DEFAULT = true;

    public static final String QUOTA_ENABLED = "quotaEnabled";
    public static final boolean QUOTA_ENABLED_DEFAULT = true;

    public static final String VALIDATION_ENABLED = "validationEnabled";
    public static final boolean VALIDATION_ENABLED_DEFAULT = true;

    public static final String CONFLICT_ENABLED = "conflictEnabled";
    public static final boolean CONFLICT_ENABLED_DEFAULT = true;

    public static final String ADAPTIVE_ENABLED = "adaptiveEnabled";
    public static final boolean ADAPTIVE_ENABLED_DEFAULT = true;

    public static final String PROXY_BASE_URL = "proxyBaseUrl";
    public static final String PROXY_BASE_URL_DEFAULT = "http://127.0.0.1:8181/onos/v1/";

    public static final String SERVICE_ACCOUNT_PASSWORD = "serviceAccountPassword";
    public static final String SERVICE_ACCOUNT_PASSWORD_DEFAULT = "gw-svc-pw";

    public static final String RATE_LIMIT_RPS = "rateLimitRps";
    public static final double RATE_LIMIT_RPS_DEFAULT = 10.0;

    public static final String RATE_LIMIT_BURST = "rateLimitBurst";
    public static final int RATE_LIMIT_BURST_DEFAULT = 20;

    public static final String MAX_BATCH_SIZE = "maxBatchSize";
    public static final int MAX_BATCH_SIZE_DEFAULT = 50;

    public static final String MAX_BODY_BYTES = "maxBodyBytes";
    public static final int MAX_BODY_BYTES_DEFAULT = 65536;

    public static final String MAX_FLOWS_PER_TENANT = "maxFlowsPerTenant";
    public static final int MAX_FLOWS_PER_TENANT_DEFAULT = 100;

    public static final String MAX_FLOWS_PER_TENANT_PER_DEVICE = "maxFlowsPerTenantPerDevice";
    public static final int MAX_FLOWS_PER_TENANT_PER_DEVICE_DEFAULT = 40;

    public static final String MAX_INTENTS_PER_TENANT = "maxIntentsPerTenant";
    public static final int MAX_INTENTS_PER_TENANT_DEFAULT = 20;

    public static final String CHURN_LIMIT = "churnLimit";
    public static final int CHURN_LIMIT_DEFAULT = 200;

    public static final String CHURN_WINDOW_SECONDS = "churnWindowSeconds";
    public static final int CHURN_WINDOW_SECONDS_DEFAULT = 60;

    public static final String MIN_PRIORITY = "minPriority";
    public static final int MIN_PRIORITY_DEFAULT = 100;

    public static final String MAX_PRIORITY = "maxPriority";
    public static final int MAX_PRIORITY_DEFAULT = 30000;

    public static final String MAX_TIMEOUT = "maxTimeout";
    public static final int MAX_TIMEOUT_DEFAULT = 300;

    public static final String THROTTLE_MODE = "throttleMode";
    public static final String THROTTLE_MODE_DEFAULT = "adaptive";

    public static final String SCORE_HALF_LIFE = "scoreHalfLife";
    public static final double SCORE_HALF_LIFE_DEFAULT = 60.0;

    public static final String LEVEL1_THRESHOLD = "level1Threshold";
    public static final double LEVEL1_THRESHOLD_DEFAULT = 10.0;

    public static final String LEVEL2_THRESHOLD = "level2Threshold";
    public static final double LEVEL2_THRESHOLD_DEFAULT = 25.0;

    public static final String LEVEL3_THRESHOLD = "level3Threshold";
    public static final double LEVEL3_THRESHOLD_DEFAULT = 50.0;

    public static final String RESTRICTED_RATE_FACTOR = "restrictedRateFactor";
    public static final double RESTRICTED_RATE_FACTOR_DEFAULT = 0.5;

    public static final String COOLDOWN = "cooldown";
    public static final int COOLDOWN_DEFAULT = 30;

    public static final String COOLDOWN_MAX = "cooldownMax";
    public static final int COOLDOWN_MAX_DEFAULT = 960;

    public static final String FIXED_PENALTY = "fixedPenalty";
    public static final int FIXED_PENALTY_DEFAULT = 60;

    public static final String WEIGHT_RATE_LIMIT = "weightRateLimit";
    public static final double WEIGHT_RATE_LIMIT_DEFAULT = 1.0;

    public static final String WEIGHT_QUOTA = "weightQuota";
    public static final double WEIGHT_QUOTA_DEFAULT = 2.0;

    public static final String WEIGHT_VALIDATION = "weightValidation";
    public static final double WEIGHT_VALIDATION_DEFAULT = 3.0;

    public static final String WEIGHT_CONFLICT = "weightConflict";
    public static final double WEIGHT_CONFLICT_DEFAULT = 5.0;

    public static final String WEIGHT_ACL = "weightAcl";
    public static final double WEIGHT_ACL_DEFAULT = 3.0;

    public static final String FIXED_EFFECT = "fixedEffect";
    public static final String FIXED_EFFECT_DEFAULT = "rate";

    public static final String FIXED_RATE_FACTOR = "fixedRateFactor";
    public static final double FIXED_RATE_FACTOR_DEFAULT = 0.1;

    public static final String AUDIT_ENABLED = "auditEnabled";
    public static final boolean AUDIT_ENABLED_DEFAULT = true;

    /** Derived from the VM (2026-11-18): the only non-tenant flow owner was org.onosproject.core. */
    public static final String AUDIT_ALLOWED_APPS = "auditAllowedApps";
    public static final String AUDIT_ALLOWED_APPS_DEFAULT = "org.onosproject.core";
}
