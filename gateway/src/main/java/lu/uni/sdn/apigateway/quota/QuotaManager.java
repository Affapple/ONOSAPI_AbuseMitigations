package lu.uni.sdn.apigateway.quota;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.intent.FlowRuleIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.IntentState;
import org.onosproject.net.intent.Key;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Per-tenant resource quotas (B3).
 *
 * <p><b>Counting.</b> A tenant's flows = its direct flows (FlowRuleService entries with
 * its ApplicationId, excluding PENDING_REMOVE/REMOVED) + the flows of its intents. For
 * an intent that has compiled, these are the real rules ({@code getInstallableIntents(key)}
 * &rarr; {@link FlowRuleIntent} rules; they carry appId org.onosproject.net.intent, so they
 * are attributed through the tenant's intents, never by appId). For an intent that has
 * not compiled yet, it is the estimate made before admission ({@link IntentFlowEstimator}),
 * kept in the ledger until the real rules exist. Intents (and their flows) count until
 * they are WITHDRAWN, FAILED or PURGE_REQ, so WITHDRAW_REQ/WITHDRAWING intents, whose
 * flows may still be on the switches, keep counting; an intent whose DELETE timed out
 * stops counting once ONOS reports it WITHDRAWN.</p>
 *
 * <p><b>Asynchrony.</b> ONOS applies flows and intents asynchronously, so a write the
 * gateway just made may not be in the store yet. A per-tenant pending ledger holds
 * the gateway's own recent flow adds/removes and intent submits (with their flow
 * estimates) until the store reflects them and is merged into the count. Flow entries
 * and not-yet-stored submits expire after {@link #PENDING_TTL_NANOS}; the estimate of a
 * stored intent is kept until it compiles or stops counting.</p>
 *
 * <p><b>Races.</b> Resources hold {@link #lock(String)} for the tenant across
 * check &rarr; apply &rarr; record, so two concurrent requests of one tenant cannot both
 * pass a check that only one of them fits. Different tenants never contend.</p>
 *
 * <p><b>Known limits.</b> A FAILED intent counts 0; if the topology recovers, ONOS may
 * recompile and reinstall it without passing admission again (a tenant cannot trigger
 * that recovery). An estimate of an intent stuck in INSTALL_REQ/COMPILING keeps counting
 * until it compiles or fails, which only pins that tenant's own quota.</p>
 *
 * <p><b>Intents vs. flow quotas.</b> A new intent is admitted only if its estimated
 * flows fit both maxFlowsPerTenant and maxFlowsPerTenantPerDevice (replacing a held key
 * swaps the old contribution for the new estimate).</p>
 */
public final class QuotaManager {

    /** How long the gateway's own writes are assumed in flight before the store shows them. */
    public static final long PENDING_TTL_NANOS = 10_000_000_000L;

    private static final double NANOS_PER_SECOND = 1e9;
    private static final Set<IntentState> NOT_COUNTED =
            EnumSet.of(IntentState.WITHDRAWN, IntentState.FAILED, IntentState.PURGE_REQ);

    private final FlowRuleService flowService;
    private final IntentService intentService;
    private final LongSupplier nanoClock;

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Ledger> ledgers = new ConcurrentHashMap<>();
    private final Map<String, ChurnWindow> churn = new ConcurrentHashMap<>();

    /** The gateway's own in-flight writes for one tenant. Guarded by its own monitor. */
    private static final class Ledger {
        final Map<Long, Long> adds = new HashMap<>();            // flowId -> ts
        final Map<Long, DeviceId> addDevices = new HashMap<>();  // flowId -> device
        final Map<Long, Long> removes = new HashMap<>();         // flowId -> ts
        final Map<Key, Long> submits = new HashMap<>();          // key -> ts of the gateway's submit
        final Map<Key, Map<DeviceId, Integer>> estimates = new HashMap<>();  // key -> estimated flows
    }

    /**
     * Creates a quota manager.
     *
     * @param flowService   flow rule service
     * @param intentService intent service
     * @param nanoClock     monotonic clock, nanos
     */
    public QuotaManager(FlowRuleService flowService, IntentService intentService, LongSupplier nanoClock) {
        this.flowService = flowService;
        this.intentService = intentService;
        this.nanoClock = nanoClock;
    }

    /**
     * Per-tenant lock; hold it across check, apply and record.
     *
     * @param tenantId tenant
     * @return lock
     */
    public ReentrantLock lock(String tenantId) {
        return locks.computeIfAbsent(tenantId, k -> new ReentrantLock());
    }

    private Ledger ledger(String tenantId) {
        return ledgers.computeIfAbsent(tenantId, k -> new Ledger());
    }

    private ChurnWindow churnOf(String tenantId) {
        return churn.computeIfAbsent(tenantId, k -> new ChurnWindow());
    }

    /**
     * Counts a tenant's usage (store state merged with in-flight writes).
     *
     * @param tenantId tenant
     * @param app      tenant ApplicationId
     * @return usage
     */
    public Usage usage(String tenantId, ApplicationId app) {
        ReentrantLock l = lock(tenantId);
        l.lock();
        try {
            return count(tenantId, app, nanoClock.getAsLong());
        } finally {
            l.unlock();
        }
    }

    /**
     * Operations in the tenant's current churn window.
     *
     * @param tenantId      tenant
     * @param windowSeconds window length
     * @return count
     */
    public int churn(String tenantId, int windowSeconds) {
        return churnOf(tenantId).count(nanoClock.getAsLong(), windowSeconds * (long) NANOS_PER_SECOND);
    }

    private Usage count(String tenantId, ApplicationId app, long now) {
        Ledger led = ledger(tenantId);
        synchronized (led) {
            return count(led, app, now);
        }
    }

    private Usage count(Ledger led, ApplicationId app, long now) {
        // direct flows in the store
        Map<Long, DeviceId> direct = new LinkedHashMap<>();
        for (FlowEntry e : flowService.getFlowEntriesById(app)) {
            if (e.state() != FlowEntry.FlowEntryState.PENDING_REMOVE
                    && e.state() != FlowEntry.FlowEntryState.REMOVED) {
                direct.put(e.id().value(), e.deviceId());
            }
        }
        // in-flight removes: drop once gone from the store or expired
        for (Iterator<Map.Entry<Long, Long>> it = led.removes.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long, Long> r = it.next();
            if (!direct.containsKey(r.getKey()) || now - r.getValue() > PENDING_TTL_NANOS) {
                it.remove();
            } else {
                direct.remove(r.getKey());
            }
        }
        // in-flight adds: drop once visible or expired
        for (Iterator<Map.Entry<Long, Long>> it = led.adds.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long, Long> a = it.next();
            if (direct.containsKey(a.getKey()) || now - a.getValue() > PENDING_TTL_NANOS) {
                it.remove();
                led.addDevices.remove(a.getKey());
            } else {
                direct.put(a.getKey(), led.addDevices.get(a.getKey()));
            }
        }
        Map<DeviceId, Integer> perDevice = new HashMap<>();
        direct.values().forEach(d -> perDevice.merge(d, 1, Integer::sum));

        // intents: stored (counted states) + gateway submits not yet in the store
        ImmutableSet.Builder<Key> keys = ImmutableSet.builder();
        ImmutableMap.Builder<Key, Map<DeviceId, Integer>> contrib = ImmutableMap.builder();
        Set<Key> stored = new HashSet<>();
        int intentFlows = 0;
        int estimated = 0;
        for (Intent i : intentService.getIntentsByAppId(app)) {
            Key k = i.key();
            stored.add(k);
            IntentState st = intentService.getIntentState(k);
            if (st != null && NOT_COUNTED.contains(st)) {
                led.estimates.remove(k);
                led.submits.remove(k);
                continue;
            }
            keys.add(k);
            Map<DeviceId, Integer> c = compiled(k);
            if (!c.isEmpty()) {
                led.estimates.remove(k);                 // real rules replace the estimate
                led.submits.remove(k);
            } else if (led.estimates.containsKey(k)) {
                c = led.estimates.get(k);                // still compiling: use the estimate
                estimated += sum(c);
            }
            contrib.put(k, c);
            intentFlows += sum(c);
            c.forEach((d, n) -> perDevice.merge(d, n, Integer::sum));
        }
        for (Iterator<Map.Entry<Key, Long>> it = led.submits.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Key, Long> s = it.next();
            if (stored.contains(s.getKey())) {
                continue;                                 // handled above
            }
            if (now - s.getValue() > PENDING_TTL_NANOS) {
                it.remove();
                led.estimates.remove(s.getKey());
                continue;
            }
            Map<DeviceId, Integer> c = led.estimates.getOrDefault(s.getKey(), ImmutableMap.of());
            keys.add(s.getKey());
            contrib.put(s.getKey(), c);
            intentFlows += sum(c);
            estimated += sum(c);
            c.forEach((d, n) -> perDevice.merge(d, n, Integer::sum));
        }
        return new Usage(ImmutableSet.copyOf(direct.keySet()), intentFlows, estimated,
                ImmutableMap.copyOf(perDevice), keys.build(), contrib.build());
    }

    private Map<DeviceId, Integer> compiled(Key k) {
        Map<DeviceId, Integer> c = new HashMap<>();
        for (Intent inst : intentService.getInstallableIntents(k)) {
            if (inst instanceof FlowRuleIntent) {
                for (FlowRule fr : ((FlowRuleIntent) inst).flowRules()) {
                    c.merge(fr.deviceId(), 1, Integer::sum);
                }
            }
        }
        return c;
    }

    private static int sum(Map<DeviceId, Integer> m) {
        int n = 0;
        for (int v : m.values()) {
            n += v;
        }
        return n;
    }

    /**
     * Checks that adding {@code rules} keeps the tenant within its quotas. A rule
     * whose FlowId the tenant already holds replaces it and adds nothing to the size
     * counts, but every rule counts as one churn operation.
     * Caller must hold the tenant lock.
     *
     * @param tenantId tenant
     * @param app      tenant ApplicationId
     * @param rules    rules to add
     * @param lim      effective limits
     * @return violation, if any
     */
    public Optional<QuotaViolation> checkFlowAdds(String tenantId, ApplicationId app, Collection<FlowRule> rules,
                                                  QuotaLimits lim) {
        long now = nanoClock.getAsLong();
        Usage u = count(tenantId, app, now);
        Map<Long, DeviceId> fresh = new LinkedHashMap<>();
        for (FlowRule r : rules) {
            if (!u.getDirectFlowIds().contains(r.id().value())) {
                fresh.put(r.id().value(), r.deviceId());
            }
        }
        if (u.totalFlows() + fresh.size() > lim.getMaxFlowsPerTenant()) {
            return Optional.of(new QuotaViolation(409, QuotaViolation.QUOTA_FLOWS, "tenant " + tenantId + " holds "
                    + u.totalFlows() + " flows (" + u.getDirectFlowIds().size() + " direct + " + u.getIntentFlows()
                    + " from intents); +" + fresh.size() + " exceeds maxFlowsPerTenant=" + lim.getMaxFlowsPerTenant(),
                    0));
        }
        Map<DeviceId, Integer> add = new HashMap<>();
        fresh.values().forEach(d -> add.merge(d, 1, Integer::sum));
        for (Map.Entry<DeviceId, Integer> e : add.entrySet()) {
            int now0 = u.onDevice(e.getKey());
            if (now0 + e.getValue() > lim.getMaxFlowsPerTenantPerDevice()) {
                return Optional.of(new QuotaViolation(409, QuotaViolation.QUOTA_FLOWS_DEVICE, "tenant " + tenantId
                        + " holds " + now0 + " flows on " + e.getKey() + "; +" + e.getValue()
                        + " exceeds maxFlowsPerTenantPerDevice=" + lim.getMaxFlowsPerTenantPerDevice(), 0));
            }
        }
        return checkChurn(tenantId, rules.size(), lim, now);
    }

    /**
     * Checks that submitting {@code key} with the given flow estimate keeps the tenant
     * within maxIntentsPerTenant, maxFlowsPerTenant, maxFlowsPerTenantPerDevice and the
     * churn limit. Resubmitting a key the tenant already holds replaces it: no +1 intent,
     * and its current flow contribution is swapped for the new estimate.
     * Caller must hold the tenant lock.
     *
     * @param tenantId tenant
     * @param app      tenant ApplicationId
     * @param key      intent key
     * @param estimate estimated compiled flows per device (IntentFlowEstimator)
     * @param lim      effective limits
     * @return violation, if any
     */
    public Optional<QuotaViolation> checkIntentSubmit(String tenantId, ApplicationId app, Key key,
                                                      Map<DeviceId, Integer> estimate, QuotaLimits lim) {
        long now = nanoClock.getAsLong();
        Usage u = count(tenantId, app, now);
        boolean held = u.getIntentKeys().contains(key);
        int add = held ? 0 : 1;
        if (u.getIntentKeys().size() + add > lim.getMaxIntentsPerTenant()) {
            return Optional.of(new QuotaViolation(409, QuotaViolation.QUOTA_INTENTS, "tenant " + tenantId + " holds "
                    + u.getIntentKeys().size() + " intents; +" + add + " exceeds maxIntentsPerTenant="
                    + lim.getMaxIntentsPerTenant(), 0));
        }
        Map<DeviceId, Integer> old = held ? u.getIntentContributions().getOrDefault(key, ImmutableMap.of())
                : ImmutableMap.of();
        int projected = u.totalFlows() - sum(old) + sum(estimate);
        if (projected > lim.getMaxFlowsPerTenant()) {
            return Optional.of(new QuotaViolation(409, QuotaViolation.QUOTA_FLOWS, "tenant " + tenantId + " holds "
                    + u.totalFlows() + " flows; this intent is estimated at " + sum(estimate) + " flows "
                    + estimate + " -> " + projected + " exceeds maxFlowsPerTenant=" + lim.getMaxFlowsPerTenant(), 0));
        }
        for (Map.Entry<DeviceId, Integer> e : estimate.entrySet()) {
            int onDev = u.onDevice(e.getKey()) - old.getOrDefault(e.getKey(), 0) + e.getValue();
            if (onDev > lim.getMaxFlowsPerTenantPerDevice()) {
                return Optional.of(new QuotaViolation(409, QuotaViolation.QUOTA_FLOWS_DEVICE, "tenant " + tenantId
                        + " holds " + u.onDevice(e.getKey()) + " flows on " + e.getKey() + "; this intent adds an "
                        + "estimated " + e.getValue() + " -> " + onDev + " exceeds maxFlowsPerTenantPerDevice="
                        + lim.getMaxFlowsPerTenantPerDevice(), 0));
            }
        }
        return checkChurn(tenantId, 1, lim, now);
    }

    /**
     * Checks only the churn limit (deletes and withdraws). Caller must hold the tenant lock.
     *
     * @param tenantId tenant
     * @param ops      operations
     * @param lim      effective limits
     * @return violation, if any
     */
    public Optional<QuotaViolation> checkChurnOnly(String tenantId, int ops, QuotaLimits lim) {
        return checkChurn(tenantId, ops, lim, nanoClock.getAsLong());
    }

    private Optional<QuotaViolation> checkChurn(String tenantId, int ops, QuotaLimits lim, long now) {
        long window = lim.getChurnWindowSeconds() * (long) NANOS_PER_SECOND;
        ChurnWindow w = churnOf(tenantId);
        long wait = w.secondsUntilRoom(now, window, lim.getChurnLimit(), ops);
        if (wait > 0) {
            return Optional.of(new QuotaViolation(429, QuotaViolation.QUOTA_CHURN, "tenant " + tenantId + " made "
                    + w.count(now, window) + " add/delete operations in the last " + lim.getChurnWindowSeconds()
                    + " s; +" + ops + " exceeds churnLimit=" + lim.getChurnLimit(), wait));
        }
        return Optional.empty();
    }

    /**
     * Records flows the gateway just applied for a tenant (pending ledger only;
     * churn is charged with {@link #recordChurn}).
     *
     * @param tenantId tenant
     * @param rules    applied rules
     */
    public void recordFlowAdds(String tenantId, Collection<? extends FlowRule> rules) {
        long now = nanoClock.getAsLong();
        Ledger led = ledger(tenantId);
        synchronized (led) {
            for (FlowRule r : rules) {
                led.adds.put(r.id().value(), now);
                led.addDevices.put(r.id().value(), r.deviceId());
                led.removes.remove(r.id().value());
            }
        }
    }

    /**
     * Records flows the gateway just removed that belonged to {@code ownerTenantId}.
     *
     * @param ownerTenantId tenant whose flows they were
     * @param rules         removed rules
     */
    public void recordFlowRemoves(String ownerTenantId, Collection<? extends FlowRule> rules) {
        long now = nanoClock.getAsLong();
        Ledger led = ledger(ownerTenantId);
        synchronized (led) {
            for (FlowRule r : rules) {
                led.removes.put(r.id().value(), now);
                led.adds.remove(r.id().value());
                led.addDevices.remove(r.id().value());
            }
        }
    }

    /**
     * Records churn operations charged to a tenant.
     *
     * @param tenantId tenant
     * @param ops      operations
     */
    public void recordChurn(String tenantId, int ops) {
        churnOf(tenantId).record(nanoClock.getAsLong(), ops);
    }

    /**
     * Forgets the gateway's pending submit/estimate for an intent it is withdrawing,
     * so a key purged before it ever showed up compiled cannot linger as a phantom
     * estimate until the pending TTL. (A stored intent still counts through its real
     * compiled rules until ONOS reports it WITHDRAWN.)
     *
     * @param tenantId tenant
     * @param key      intent key
     */
    public void forgetIntent(String tenantId, Key key) {
        Ledger led = ledger(tenantId);
        synchronized (led) {
            led.submits.remove(key);
            led.estimates.remove(key);
        }
    }

    /**
     * Records an intent the gateway just submitted and its flow estimate (pending
     * ledger only; churn is charged with {@link #recordChurn}).
     *
     * @param tenantId tenant
     * @param key      intent key
     * @param estimate estimated compiled flows per device
     */
    public void recordIntentSubmit(String tenantId, Key key, Map<DeviceId, Integer> estimate) {
        long now = nanoClock.getAsLong();
        Ledger led = ledger(tenantId);
        synchronized (led) {
            led.submits.put(key, now);
            led.estimates.put(key, ImmutableMap.copyOf(estimate));
        }
    }
}
