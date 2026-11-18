package lu.uni.sdn.apigateway.quota;

import com.google.common.collect.ImmutableSet;
import lombok.Value;
import lu.uni.sdn.apigateway.Props;
import lu.uni.sdn.apigateway.tenant.Tenant;

import java.util.Dictionary;
import java.util.Map;
import java.util.Set;

/**
 * Quota limits (B3). The component-config values are the defaults; a tenant's
 * "quotas" object in tenants.json overrides them per key.
 */
@Value
public class QuotaLimits {

    /** Names allowed in a tenant's "quotas" object (validated by TenantRegistry). */
    public static final String MAX_FLOWS = "maxFlowsPerTenant";
    public static final String MAX_FLOWS_PER_DEVICE = "maxFlowsPerTenantPerDevice";
    public static final String MAX_INTENTS = "maxIntentsPerTenant";
    public static final String CHURN_LIMIT = "churnLimit";
    public static final Set<String> KEYS = ImmutableSet.of(MAX_FLOWS, MAX_FLOWS_PER_DEVICE, MAX_INTENTS, CHURN_LIMIT);

    /** Component-config defaults (property names = the quota keys above). */
    public static final int MAX_FLOWS_DEFAULT = 100;
    public static final int MAX_FLOWS_PER_DEVICE_DEFAULT = 40;
    public static final int MAX_INTENTS_DEFAULT = 20;
    public static final int CHURN_LIMIT_DEFAULT = 200;
    public static final String CHURN_WINDOW_SECONDS = "churnWindowSeconds";
    public static final int CHURN_WINDOW_SECONDS_DEFAULT = 60;

    int maxFlowsPerTenant;
    int maxFlowsPerTenantPerDevice;
    int maxIntentsPerTenant;
    /** Max add+delete operations per churn window. */
    int churnLimit;
    int churnWindowSeconds;

    /**
     * Parses the default (component-config) quota limits.
     *
     * @param p component properties (null: defaults)
     * @return limits
     */
    public static QuotaLimits from(Dictionary<?, ?> p) {
        return new QuotaLimits(Props.positiveInt(p, MAX_FLOWS, MAX_FLOWS_DEFAULT),
                Props.positiveInt(p, MAX_FLOWS_PER_DEVICE, MAX_FLOWS_PER_DEVICE_DEFAULT),
                Props.positiveInt(p, MAX_INTENTS, MAX_INTENTS_DEFAULT),
                Props.positiveInt(p, CHURN_LIMIT, CHURN_LIMIT_DEFAULT),
                Props.positiveInt(p, CHURN_WINDOW_SECONDS, CHURN_WINDOW_SECONDS_DEFAULT));
    }

    /**
     * Applies a tenant's overrides.
     *
     * @param tenant tenant (may be null: no overrides)
     * @return effective limits for that tenant
     */
    public QuotaLimits forTenant(Tenant tenant) {
        if (tenant == null || tenant.getQuotas().isEmpty()) {
            return this;
        }
        Map<String, Long> q = tenant.getQuotas();
        return new QuotaLimits(pick(q, MAX_FLOWS, maxFlowsPerTenant),
                pick(q, MAX_FLOWS_PER_DEVICE, maxFlowsPerTenantPerDevice),
                pick(q, MAX_INTENTS, maxIntentsPerTenant),
                pick(q, CHURN_LIMIT, churnLimit), churnWindowSeconds);
    }

    private static int pick(Map<String, Long> q, String key, int def) {
        Long v = q.get(key);
        return v == null ? def : (int) Math.min(Integer.MAX_VALUE, v);
    }
}
