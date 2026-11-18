package lu.uni.sdn.apigateway.acl;

/**
 * Reason codes carried in every gateway error body: {"code", "reason", "message"}.
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
}
