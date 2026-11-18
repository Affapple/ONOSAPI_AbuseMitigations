package lu.uni.sdn.apigateway;

import java.util.Map;
import java.util.Set;

import lu.uni.sdn.apigateway.audit.BypassAuditor;
import lu.uni.sdn.apigateway.audit.RejectionCounters;
import lu.uni.sdn.apigateway.quota.QuotaLimits;
import lu.uni.sdn.apigateway.quota.QuotaManager;
import lu.uni.sdn.apigateway.ratelimit.TokenBucketLimiter;
import lu.uni.sdn.apigateway.throttle.ThrottleManager;
import lu.uni.sdn.apigateway.throttle.ThrottleSettings;
import lu.uni.sdn.apigateway.validation.ValidationLimits;

/**
 * Gateway state shared with the REST layer.
 */
public interface GatewayService {

    /** ONOS application name of the gateway itself. */
    String APP_NAME = "lu.uni.sdn.api_gateway";

    /**
     * Current registry + tenant app ids, as one immutable snapshot
     * (empty until tenants.json is loaded through netcfg).
     *
     * @return snapshot
     */
    GatewaySnapshot snapshot();

    /**
     * Current module switches.
     *
     * @return toggles snapshot
     */
    ModuleToggles toggles();

    /**
     * Current read-proxy settings.
     *
     * @return proxy settings
     */
    ProxySettings proxySettings();

    /**
     * Current rate-limit numbers.
     *
     * @return limits snapshot
     */
    Limits limits();

    /**
     * The per-tenant token buckets (shared by all requests).
     *
     * @return limiter
     */
    TokenBucketLimiter rateLimiter();

    /**
     * Current default quota limits (component config; tenants.json may override per tenant).
     *
     * @return limits snapshot
     */
    QuotaLimits quotaLimits();

    /**
     * Per-tenant quota accounting.
     *
     * @return quota manager
     */
    QuotaManager quotaManager();

    /**
     * Current payload validation limits.
     *
     * @return limits snapshot
     */
    ValidationLimits validationLimits();

    /**
     * Records a B5 conflict violation of a tenant (submit-time 409 or compiled conflict).
     * B6 (adaptive throttling) will consume these.
     *
     * @param tenantId tenant
     * @param reason   reason code
     * @param message  detail
     */
    void recordConflict(String tenantId, String reason, String message);

    /**
     * Conflict violations per reason code of a tenant.
     *
     * @param tenantId tenant
     * @return reason to count
     */
    Map<String, Integer> conflicts(String tenantId);

    /**
     * Last conflict message of a tenant.
     *
     * @param tenantId tenant
     * @return message, or null
     */
    String lastConflict(String tenantId);

    /**
     * Per-tenant adaptive throttling state (B6).
     *
     * @return throttle manager
     */
    ThrottleManager throttleManager();

    /**
     * Current B6 settings.
     *
     * @return settings snapshot
     */
    ThrottleSettings throttleSettings();

    /**
     * Adds a rejection of a tenant to its B6 score (no-op while adaptiveEnabled is off).
     *
     * @param tenantId          tenant (null: ignored)
     * @param reason            rejection reason code
     * @param formatOnFlowWrite the rejected request was a POST/DELETE to /gw/flows or /gw/intents
     */
    void recordViolation(String tenantId, String reason, boolean formatOnFlowWrite);

    /**
     * Bypass auditor (B7).
     *
     * @return auditor
     */
    BypassAuditor auditor();

    /**
     * Per-tenant rejection counters (all modules).
     *
     * @return counters
     */
    RejectionCounters rejections();

    /**
     * Current audit allowlist of non-tenant appIds.
     *
     * @return appIds
     */
    Set<String> auditAllowedApps();
}
