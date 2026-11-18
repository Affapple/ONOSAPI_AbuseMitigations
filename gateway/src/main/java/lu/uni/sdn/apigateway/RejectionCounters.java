package lu.uni.sdn.apigateway;

import com.google.common.collect.ImmutableMap;
import lu.uni.sdn.apigateway.acl.Reasons;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every gateway rejection, counted per tenant (or "_other" for non-tenant callers),
 * per module and per reason code (conflicts are shown on /gw/admin/tenants). Thread-safe; bounded by the
 * number of tenants x reason codes.
 */
public final class RejectionCounters {

    /** Bucket for callers that are not tenant accounts. */
    public static final String OTHER = "_other";

    private final Map<String, Map<String, AtomicLong>> counts = new ConcurrentHashMap<>();
    /** "tenant|module" -&gt; "reason: message" of the last rejection. */
    private final Map<String, String> last = new ConcurrentHashMap<>();

    /**
     * Counts one rejection and remembers its message.
     *
     * @param tenant  tenant id, or null for non-tenant callers
     * @param reason  reason code
     * @param message detail (may be null)
     */
    public void count(String tenant, String reason, String message) {
        if (reason == null) {
            return;
        }
        String t = tenant == null ? OTHER : tenant;
        counts.computeIfAbsent(t, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
        last.put(t + "|" + module(reason), reason + (message == null ? "" : ": " + message));
    }

    /**
     * Counts of one tenant and module, by reason.
     *
     * @param tenant tenant id
     * @param module module name (see {@link #module})
     * @return reason -&gt; count
     */
    public Map<String, Long> of(String tenant, String module) {
        return snapshot().getOrDefault(tenant, ImmutableMap.of()).getOrDefault(module, ImmutableMap.of());
    }

    /**
     * Last rejection of one tenant and module.
     *
     * @param tenant tenant id
     * @param module module name
     * @return "reason: message", or null
     */
    public String last(String tenant, String module) {
        return last.get(tenant + "|" + module);
    }

    /** tenant -&gt; module -&gt; reason -&gt; count. */
    private Map<String, Map<String, Map<String, Long>>> snapshot() {
        Map<String, Map<String, Map<String, Long>>> out = new TreeMap<>();
        counts.forEach((t, reasons) -> {
            Map<String, Map<String, Long>> byModule = new TreeMap<>();
            reasons.forEach((r, n) -> byModule.computeIfAbsent(module(r), k -> new TreeMap<>()).put(r, n.get()));
            out.put(t, byModule);
        });
        return ImmutableMap.copyOf(out);
    }

    /**
     * Module that produced a reason code.
     *
     * @param reason reason code
     * @return acl, ratelimit, quota, validation, conflict, throttle, or other
     */
    public static String module(String reason) {
        return Reasons.category(reason).name().toLowerCase();
    }
}
