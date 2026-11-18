package lu.uni.sdn.apigateway;

import lu.uni.sdn.apigateway.quota.QuotaLimits;
import lu.uni.sdn.apigateway.quota.QuotaManager;
import lu.uni.sdn.apigateway.ratelimit.RateLimitSettings;
import lu.uni.sdn.apigateway.ratelimit.TokenBucketLimiter;
import lu.uni.sdn.apigateway.throttle.ThrottleManager;
import lu.uni.sdn.apigateway.throttle.ThrottleSettings;
import lu.uni.sdn.apigateway.validation.ValidationLimits;

/**
 * Gateway state shared with the REST layer. Every settings object is an immutable
 * snapshot replaced on {@code cfg set}.
 */
public interface GatewayService {

    /** ONOS application name of the gateway itself. */
    String APP_NAME = "lu.uni.sdn.api_gateway";

    /**
     * Registry + tenant app ids (empty until tenants.json is loaded through netcfg).
     *
     * @return snapshot
     */
    GatewaySnapshot getSnapshot();

    /**
     * Module on/off switches.
     *
     * @return toggles
     */
    ModuleToggles getToggles();

    /**
     * Read-proxy settings.
     *
     * @return settings
     */
    ProxySettings getProxySettings();

    /**
     * B2 settings.
     *
     * @return settings
     */
    RateLimitSettings getRateLimitSettings();

    /**
     * B2 per-tenant token buckets.
     *
     * @return limiter
     */
    TokenBucketLimiter getLimiter();

    /**
     * B3 default quota limits (tenants.json may override per tenant).
     *
     * @return limits
     */
    QuotaLimits getQuotaLimits();

    /**
     * B3 per-tenant quota accounting.
     *
     * @return quota manager
     */
    QuotaManager getQuotaManager();

    /**
     * B4 limits.
     *
     * @return limits
     */
    ValidationLimits getValidationLimits();

    /**
     * B6 settings.
     *
     * @return settings
     */
    ThrottleSettings getThrottleSettings();

    /**
     * B6 per-tenant throttling state.
     *
     * @return throttle manager
     */
    ThrottleManager getThrottleManager();

    /**
     * Rejection counters of every module, per tenant.
     *
     * @return counters
     */
    RejectionCounters getRejections();

    /**
     * The one place a rejection is recorded: counted per tenant/module/reason and, for
     * tenant callers with adaptiveEnabled, added to the B6 score (which may cap the
     * tenant's B2 bucket when a fixed penalty starts).
     *
     * @param tenantId          tenant, or null for non-tenant callers
     * @param reason            reason code
     * @param message           detail (may be null)
     * @param formatOnFlowWrite the request was a POST/DELETE to /gw/flows or /gw/intents
     */
    void recordRejection(String tenantId, String reason, String message, boolean formatOnFlowWrite);
}
