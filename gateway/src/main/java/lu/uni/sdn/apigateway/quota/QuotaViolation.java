package lu.uni.sdn.apigateway.quota;

import lombok.Value;

/**
 * A rejected write: HTTP status (409 for size quotas, 429 for churn), reason code,
 * message, and Retry-After seconds (churn only, else 0).
 */
@Value
public class QuotaViolation {
    /** Reason: tenant would hold more than maxFlowsPerTenant flows. */
    public static final String QUOTA_FLOWS = "quota_flows";
    /** Reason: tenant would hold more than maxFlowsPerTenantPerDevice flows on a device. */
    public static final String QUOTA_FLOWS_DEVICE = "quota_flows_device";
    /** Reason: tenant would hold more than maxIntentsPerTenant intents. */
    public static final String QUOTA_INTENTS = "quota_intents";
    /** Reason: more than churnLimit add+delete operations in the churn window. */
    public static final String QUOTA_CHURN = "quota_churn";

    int status;
    String reason;
    String message;
    long retryAfterSeconds;
}
