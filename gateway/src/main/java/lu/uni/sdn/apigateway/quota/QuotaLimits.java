package lu.uni.sdn.apigateway.quota;

import com.google.common.collect.ImmutableSet;
import lombok.Value;
import lu.uni.sdn.apigateway.tenant.Tenant;

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

    int maxFlowsPerTenant;
    int maxFlowsPerTenantPerDevice;
    int maxIntentsPerTenant;
    /** Max add+delete operations per churn window. */
    int churnLimit;
    int churnWindowSeconds;

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
