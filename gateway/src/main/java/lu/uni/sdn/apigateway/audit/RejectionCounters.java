package lu.uni.sdn.apigateway.audit;

import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every gateway rejection, counted per tenant (or "_other" for non-tenant callers),
 * per module and per reason code, for /gw/admin/audit. Thread-safe; bounded by the
 * number of tenants x reason codes.
 */
public final class RejectionCounters {

    /** Bucket for callers that are not tenant accounts. */
    public static final String OTHER = "_other";

    private final Map<String, Map<String, AtomicLong>> counts = new ConcurrentHashMap<>();

    /**
     * Counts one rejection.
     *
     * @param tenant tenant id, or null for non-tenant callers
     * @param reason reason code
     */
    public void count(String tenant, String reason) {
        if (reason == null) {
            return;
        }
        counts.computeIfAbsent(tenant == null ? OTHER : tenant, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * tenant -&gt; module -&gt; reason -&gt; count.
     *
     * @return snapshot
     */
    public Map<String, Map<String, Map<String, Long>>> snapshot() {
        Map<String, Map<String, Map<String, Long>>> out = new TreeMap<>();
        counts.forEach((t, reasons) -> {
            Map<String, Map<String, Long>> byModule = new TreeMap<>();
            reasons.forEach((r, n) -> byModule.computeIfAbsent(module(r), k -> new TreeMap<>()).put(r, n.get()));
            out.put(t, byModule);
        });
        return ImmutableMap.copyOf(out);
    }

    /** Clears all counters. */
    public void reset() {
        counts.clear();
    }

    /**
     * Module that produced a reason code.
     *
     * @param reason reason code
     * @return acl, ratelimit, quota, validation, conflict, throttle, or other
     */
    public static String module(String reason) {
        switch (reason) {
            case "acl_denied":
            case "unknown_account":
            case "bad_path":
            case "device_not_owned":
            case "port_not_owned":
            case "resource_not_owned":
            case "instruction_not_allowed":
            case "endpoint_not_owned":
            case "not_owner":
            case "tenant_mismatch":
            case "unsupported_intent_type":
                return "acl";
            case "rate_limited":
            case "body_too_large":
            case "batch_too_large":
            case "batch_mismatch":
                return "ratelimit";
            case "quota_flows":
            case "quota_flows_device":
            case "quota_intents":
            case "quota_churn":
                return "quota";
            case "bad_priority":
            case "permanent_not_allowed":
            case "bad_timeout":
            case "criterion_not_allowed":
            case "missing_prerequisite":
            case "controller_output":
            case "match_not_owned":
            case "drop_not_owned":
            case "selector_not_owned":
            case "rewrite_not_owned":
            case "constraints_not_allowed":
                return "validation";
            case "spoofed_source":
            case "foreign_rewrite":
            case "dst_capture":
            case "overlap_conflict":
            case "compiled_conflict":
                return "conflict";
            case "throttled_cooldown":
            case "throttled_blocked":
            case "throttled_fixed":
                return "throttle";
            default:
                return "other";
        }
    }
}
