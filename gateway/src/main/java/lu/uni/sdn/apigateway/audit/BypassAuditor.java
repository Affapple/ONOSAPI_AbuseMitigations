package lu.uni.sdn.apigateway.audit;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import lombok.Value;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * B7 auditor: flags flow rules and intents that appear in ONOS without having gone
 * through the gateway. Pure logic (no ONOS services); thread-safe; bounded memory.
 *
 * <p><b>How gateway writes are told apart.</b> The resources mark what they submit BEFORE
 * submitting it: intents by {@code IntentId} (unique per submission, so a later
 * /onos/v1 submission under the same tenant key is still caught), flows by
 * {@code FlowId}. FlowIds are derived from the rule's content, so a bypass that re-adds
 * a byte-identical flow the gateway created earlier cannot be told apart (documented
 * limitation). Marks are never removed on delete/purge (that would race with a quick
 * delete-and-re-add and cause false positives); they live in LRU-bounded sets.</p>
 *
 * <p><b>Classification.</b></p>
 * <ul>
 *   <li>flow with a tenant appId: OK if the gateway marked its FlowId, else bypass
 *       ("tenant appId, not created through the gateway", the V3 spoof);</li>
 *   <li>flow with appId org.onosproject.net.intent: OK only if it is one of the compiled
 *       rules of a tenant intent, else bypass;</li>
 *   <li>flow or intent with an allowlisted appId (configurable; default derived from the
 *       VM: org.onosproject.core): OK;</li>
 *   <li>intent with a tenant appId: OK if the gateway marked its IntentId, else bypass
 *       ("not submitted through the gateway", the V7 spoof);</li>
 *   <li>anything else: bypass.</li>
 * </ul>
 */
public final class BypassAuditor {

    /** appId of intent-compiled flows. */
    public static final String INTENT_APP = "org.onosproject.net.intent";
    static final int MAX_FLOW_MARKS = 100_000;
    static final int MAX_INTENT_MARKS = 20_000;
    static final int MAX_FLAGGED = 20_000;
    /** Recent bypass events kept for /gw/admin/audit. */
    public static final int RECENT = 100;

    /** One detected bypass. */
    @Value
    public static class Bypass {
        long epochMillis;
        String kind;
        String device;
        String appId;
        String id;
        String why;
    }

    /** Counters + recent events, for /gw/admin/audit. */
    @Value
    public static class Snapshot {
        long total;
        ImmutableMap<String, Long> byKind;
        ImmutableMap<String, Long> byAppId;
        ImmutableMap<String, Long> byWhy;
        ImmutableList<Bypass> recent;
        int trackedGatewayFlows;
        int trackedGatewayIntents;
    }

    private final Set<Long> gatewayFlows = lru(MAX_FLOW_MARKS);
    private final Set<Long> gatewayIntents = lru(MAX_INTENT_MARKS);
    private final Set<String> flagged = lru(MAX_FLAGGED);
    private final Deque<Bypass> recent = new ArrayDeque<>();
    private final AtomicLong total = new AtomicLong();
    private final Map<String, AtomicLong> byKind = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> byApp = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> byWhy = new ConcurrentHashMap<>();

    private static <T> Set<T> lru(int max) {
        return Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<T, Boolean>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<T, Boolean> e) {
                return size() > max;
            }
        }));
    }

    /**
     * Marks a flow the gateway is about to apply.
     *
     * @param flowId FlowId value
     */
    public void markGatewayFlow(long flowId) {
        gatewayFlows.add(flowId);
    }

    /**
     * Marks an intent the gateway is about to submit.
     *
     * @param intentId IntentId fingerprint
     */
    public void markGatewayIntent(long intentId) {
        gatewayIntents.add(intentId);
    }

    /**
     * Classifies a flow rule.
     *
     * @param flowId         FlowId value
     * @param appName        name of the rule's ApplicationId
     * @param isTenantApp    tenant appId test
     * @param tenantIntentFlow whether a FlowId is a compiled rule of a tenant intent
     * @param allowed        allowlisted appIds
     * @return why it is a bypass, or empty if it is fine
     */
    public Optional<String> classifyFlow(long flowId, String appName, Predicate<String> isTenantApp,
                                         LongPredicate tenantIntentFlow, Set<String> allowed) {
        if (isTenantApp.test(appName)) {
            return gatewayFlows.contains(flowId) ? Optional.empty()
                    : Optional.of("tenant appId, not created through the gateway");
        }
        if (INTENT_APP.equals(appName)) {
            return tenantIntentFlow.test(flowId) ? Optional.empty()
                    : Optional.of("intent-compiled flow not from a tenant intent");
        }
        if (allowed.contains(appName)) {
            return Optional.empty();
        }
        return Optional.of("appId is not a tenant, intent or allowlisted app");
    }

    /**
     * Classifies an intent.
     *
     * @param intentId    IntentId fingerprint
     * @param appName     name of the intent's ApplicationId
     * @param isTenantApp tenant appId test
     * @param allowed     allowlisted appIds
     * @return why it is a bypass, or empty if it is fine
     */
    public Optional<String> classifyIntent(long intentId, String appName, Predicate<String> isTenantApp,
                                           Set<String> allowed) {
        if (isTenantApp.test(appName)) {
            return gatewayIntents.contains(intentId) ? Optional.empty()
                    : Optional.of("tenant appId, not submitted through the gateway");
        }
        if (allowed.contains(appName)) {
            return Optional.empty();
        }
        return Optional.of("appId is not a tenant or allowlisted app");
    }

    /**
     * Records a bypass once per (kind, id, appId).
     *
     * @param b bypass
     * @return true if it was new
     */
    public boolean report(Bypass b) {
        if (!flagged.add(b.getKind() + "|" + b.getAppId() + "|" + b.getId())) {
            return false;
        }
        total.incrementAndGet();
        byKind.computeIfAbsent(b.getKind(), k -> new AtomicLong()).incrementAndGet();
        byApp.computeIfAbsent(b.getAppId(), k -> new AtomicLong()).incrementAndGet();
        byWhy.computeIfAbsent(b.getWhy(), k -> new AtomicLong()).incrementAndGet();
        synchronized (recent) {
            recent.addLast(b);
            while (recent.size() > RECENT) {
                recent.removeFirst();
            }
        }
        return true;
    }

    /**
     * Counters and recent events.
     *
     * @return snapshot
     */
    public Snapshot snapshot() {
        ImmutableList<Bypass> r;
        synchronized (recent) {
            r = ImmutableList.copyOf(recent);
        }
        return new Snapshot(total.get(), copy(byKind), copy(byApp), copy(byWhy), r, gatewayFlows.size(),
                gatewayIntents.size());
    }

    /** Clears counters, recent events and the de-duplication set (marks are kept). */
    public void reset() {
        total.set(0);
        byKind.clear();
        byApp.clear();
        byWhy.clear();
        flagged.clear();
        synchronized (recent) {
            recent.clear();
        }
    }

    /**
     * Parses a comma-separated allowlist.
     *
     * @param csv comma-separated appIds
     * @return set
     */
    public static Set<String> parseAllowlist(String csv) {
        ImmutableSet.Builder<String> b = ImmutableSet.builder();
        if (csv != null) {
            for (String s : csv.split(",")) {
                if (!s.trim().isEmpty()) {
                    b.add(s.trim());
                }
            }
        }
        return b.build();
    }

    private static ImmutableMap<String, Long> copy(Map<String, AtomicLong> m) {
        Map<String, Long> t = new TreeMap<>();
        m.forEach((k, v) -> t.put(k, v.get()));
        return ImmutableMap.copyOf(t);
    }
}
