package lu.uni.sdn.apigateway.acl;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * Reason codes carried in every gateway error body: {"code", "reason", "message"},
 * and the one table mapping each code to the module that produced it.
 */
public final class Reasons {

    private Reasons() {
    }

    // Layer 1 (filter)
    /** Path is malformed (empty/dot segments, matrix params, encoded slash). 400. */
    public static final String BAD_PATH = "bad_path";
    /** Authenticated, but the account is not in tenants.json (or is the service account). 403. */
    public static final String UNKNOWN_ACCOUNT = "unknown_account";
    /** Method/path is not allowed for the caller's gateway role (default deny). 403. */
    public static final String ACL_DENIED = "acl_denied";
    /** Gateway service unavailable (app stopping). 503. */
    public static final String UNAVAILABLE = "gateway_unavailable";

    // Rate limit (B2, in the filter)
    /** Tenant's token bucket is empty. 429 with Retry-After. */
    public static final String RATE_LIMITED = "rate_limited";
    /** Body larger than maxBodyBytes. 413. */
    public static final String BODY_TOO_LARGE = "body_too_large";
    /** Flows acted on differ from the flows the rate limiter charged for. 400. */
    public static final String BATCH_MISMATCH = "batch_mismatch";
    /** More flows in one batch than maxBatchSize. 413. */
    public static final String BATCH_TOO_LARGE = "batch_too_large";

    // Ownership (ACL layer, in the resources)
    /** Flow targets a device the registry does not know. 403. */
    public static final String DEVICE_NOT_OWNED = "device_not_owned";
    /** Flow uses a port that is neither the tenant's edge port nor a trunk. 403. */
    public static final String PORT_NOT_OWNED = "port_not_owned";
    /** Flow references a group/meter/queue, which tenants do not own. 403. */
    public static final String RESOURCE_NOT_OWNED = "resource_not_owned";
    /** Flow treatment uses an instruction outside the allowlist (TABLE, METADATA, MPLS, ...). 403. */
    public static final String INSTRUCTION_NOT_ALLOWED = "instruction_not_allowed";
    /** Intent endpoint is not one of the tenant's hosts / edge ports. 403. */
    public static final String ENDPOINT_NOT_OWNED = "endpoint_not_owned";
    /** DELETE/GET of a rule that belongs to another ApplicationId. 403. */
    public static final String NOT_OWNER = "not_owner";
    /** Caller asked for another tenant's data (?tenant=). 403. */
    public static final String TENANT_MISMATCH = "tenant_mismatch";

    // Request errors
    /** Admin write without ?tenant=, or unknown tenant. 400. */
    public static final String TENANT_REQUIRED = "tenant_required";
    /** Body does not parse / decode. 400. */
    public static final String BAD_REQUEST = "bad_request";
    /** Intent type whose endpoints the gateway cannot check. 400. */
    public static final String UNSUPPORTED_INTENT_TYPE = "unsupported_intent_type";
    /** 404. */
    public static final String NOT_FOUND = "not_found";
    /** 405. */
    public static final String METHOD_NOT_ALLOWED = "method_not_allowed";
    /** Identity property missing on a request that reached a resource. 500. */
    public static final String NO_IDENTITY = "no_identity";
    /** The decoded rule/intent does not carry the imposed tenant ApplicationId. 500. */
    public static final String APPID_NOT_IMPOSED = "appid_not_imposed";
    /** Upstream (/onos/v1) unreachable or rejected the service account. 502. */
    public static final String UPSTREAM_ERROR = "upstream_error";
    /** /onos/v1 answered an error to a proxied request (status passed through). */
    public static final String UPSTREAM_REJECTED = "upstream_rejected";
    /** Anything unexpected. 500. */
    public static final String INTERNAL_ERROR = "internal_error";

    /** Module that produced a reason code; also selects its B6 throttle weight. */
    public enum Category { ACL, RATELIMIT, QUOTA, VALIDATION, CONFLICT, THROTTLE, OTHER }

    private static final Map<String, Category> CATEGORY = table(
            Category.ACL, "acl_denied unknown_account bad_path device_not_owned port_not_owned resource_not_owned "
                    + "instruction_not_allowed endpoint_not_owned not_owner tenant_mismatch unsupported_intent_type",
            Category.RATELIMIT, "rate_limited body_too_large batch_too_large batch_mismatch",
            Category.QUOTA, "quota_flows quota_flows_device quota_intents quota_churn",
            Category.VALIDATION, "bad_priority permanent_not_allowed bad_timeout criterion_not_allowed "
                    + "missing_prerequisite controller_output match_not_owned drop_not_owned selector_not_owned "
                    + "rewrite_not_owned constraints_not_allowed",
            Category.CONFLICT, "spoofed_source foreign_rewrite dst_capture overlap_conflict compiled_conflict",
            Category.THROTTLE, "throttled_cooldown throttled_blocked throttled_fixed");

    /**
     * Category of a reason code.
     *
     * @param reason reason code (may be null)
     * @return its category, OTHER if unknown
     */
    public static Category category(String reason) {
        return reason == null ? Category.OTHER : CATEGORY.getOrDefault(reason, Category.OTHER);
    }

    private static Map<String, Category> table(Object... categoryThenCodes) {
        ImmutableMap.Builder<String, Category> b = ImmutableMap.builder();
        for (int i = 0; i < categoryThenCodes.length; i += 2) {
            for (String code : ((String) categoryThenCodes[i + 1]).split(" ")) {
                b.put(code, (Category) categoryThenCodes[i]);
            }
        }
        return b.build();
    }
}
