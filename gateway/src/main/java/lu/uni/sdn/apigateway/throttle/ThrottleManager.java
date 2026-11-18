package lu.uni.sdn.apigateway.throttle;

import com.google.common.collect.ImmutableList;
import lombok.Value;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Adaptive throttling (B6), per tenant. Thread-safe; clock injectable (nanos).
 *
 * <p><b>Score.</b> Every rejection of a tenant request adds its weight (rate limit +1,
 * quota +2, validation +3, conflict/spoof +5, ACL/ownership +3; see {@link #weightFor}).
 * The score decays exponentially with half-life {@code scoreHalfLifeSeconds}.</p>
 *
 * <p><b>Levels</b> (adaptive mode). Entering is immediate when the score reaches a
 * level's threshold: 1 Restricted (refill rate x restrictedRateFactor), 2 Cooldown
 * (writes 429 for the cooldown, which starts when level 2 is entered FROM BELOW and
 * doubles on every such re-entry, capped at cooldownMaxSeconds; after it the tenant
 * stays restricted), 3 Blocked (everything 403). Hysteresis: a level drops by one only
 * when the score falls below half of that level's threshold (so Blocked ends below
 * level3/2, i.e. 25 with the defaults, or on admin unblock). The cooldown doubling
 * resets when the tenant is back at level 0.</p>
 *
 * <p><b>Own rejections add nothing.</b> 429/403 answers produced BY this module carry
 * weight 0: otherwise a blocked tenant's retries would keep its score up and it could
 * never decay out of the block, and a throttled tenant would escalate merely by being
 * throttled. Escalation comes only from requests that reach the other modules.</p>
 *
 * <p><b>Fixed mode</b> (Lab2 comparison): no levels, no escalation; any violation (weight
 * &gt; 0) starts (or extends) a fixed penalty of fixedPenaltySeconds. Effect "rate" (default,
 * closest to Lab2's fixed 60 s rate cap): the tenant's B2 refill rate x fixedRateFactor on
 * all requests. Effect "block-writes": writes 429 throttled_fixed, reads untouched.</p>
 */
public final class ThrottleManager {

    /** History entries kept per tenant. */
    public static final int HISTORY = 20;

    private static final double NANOS_PER_SECOND = 1e9;

    private final LongSupplier nanoClock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** What the filter should do with a request. */
    @Value
    public static class Decision {
        /** 0 = allowed; else the HTTP status to answer. */
        int status;
        String reason;
        String message;
        long retryAfterSeconds;
        /** Factor for the B2 refill rate (1.0 = unchanged). */
        double rateFactor;

        public boolean isAllowed() {
            return status == 0;
        }
    }

    /** One recorded violation. */
    @Value
    public static class Event {
        long atNanos;
        String reason;
        double weight;
        double scoreAfter;
        int levelAfter;
    }

    /** Snapshot for /gw/admin. */
    @Value
    public static class Status {
        double score;
        int level;
        long cooldownRemainingSeconds;
        int cooldownEntries;
        long fixedRemainingSeconds;
        ImmutableList<Event> history;
    }

    private static final class State {
        double score;
        long last;
        int level;
        long cooldownUntil;
        int cooldownEntries;
        long fixedUntil;
        final Deque<Event> history = new ArrayDeque<>();

        State(long now) {
            last = now;
        }
    }

    /**
     * Creates a manager.
     *
     * @param nanoClock monotonic clock, nanos
     */
    public ThrottleManager(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /**
     * Weight of a rejection reason code; 0 for reasons that are not violations
     * (including this module's own throttled_* answers).
     *
     * @param reason reason code
     * @param s      settings
     * @return weight
     */
    public static double weightFor(String reason, ThrottleSettings s) {
        return weightFor(reason, false, s);
    }

    /**
     * Weight of a rejection reason code. A {@code bad_request} (body does not parse or
     * decode) on a write to /gw/flows or /gw/intents counts as a validation violation, so
     * fuzzing the decoders escalates; elsewhere it, and {@code not_found} (e.g. the async
     * store lag a legitimate workload hits), stay free.
     *
     * @param reason          reason code
     * @param formatOnFlowWrite the request was a POST/DELETE to /gw/flows or /gw/intents
     * @param s               settings
     * @return weight
     */
    public static double weightFor(String reason, boolean formatOnFlowWrite, ThrottleSettings s) {
        if (reason == null) {
            return 0;
        }
        if (formatOnFlowWrite && "bad_request".equals(reason)) {
            return s.getWeightValidation();
        }
        switch (reason) {
            case "rate_limited":
            case "body_too_large":
            case "batch_too_large":
            case "batch_mismatch":
                return s.getWeightRateLimit();
            case "quota_flows":
            case "quota_flows_device":
            case "quota_intents":
            case "quota_churn":
                return s.getWeightQuota();
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
                return s.getWeightValidation();
            case "spoofed_source":
            case "foreign_rewrite":
            case "dst_capture":
            case "overlap_conflict":
            case "compiled_conflict":
                return s.getWeightConflict();
            case "acl_denied":
            case "bad_path":
            case "device_not_owned":
            case "port_not_owned":
            case "resource_not_owned":
            case "instruction_not_allowed":
            case "endpoint_not_owned":
            case "not_owner":
            case "tenant_mismatch":
            case "unsupported_intent_type":
                return s.getWeightAcl();
            default:
                return 0;
        }
    }

    /**
     * Records a violation of a tenant.
     *
     * @param tenant tenant id
     * @param reason reason code
     * @param weight weight (&lt;= 0 is ignored)
     * @param s      settings
     */
    public void record(String tenant, String reason, double weight, ThrottleSettings s) {
        if (weight <= 0) {
            return;
        }
        long now = nanoClock.getAsLong();
        State st = states.computeIfAbsent(tenant, k -> new State(now));
        synchronized (st) {
            update(st, now, s);
            st.score += weight;
            if (s.isFixed()) {
                st.fixedUntil = Math.max(st.fixedUntil, now + seconds(s.getFixedPenaltySeconds()));
            } else {
                update(st, now, s);
            }
            st.history.addLast(new Event(now, reason, weight, st.score, st.level));
            while (st.history.size() > HISTORY) {
                st.history.removeFirst();
            }
        }
    }

    /**
     * Decides whether a request of a tenant may proceed.
     *
     * @param tenant tenant id
     * @param write  POST/PUT/DELETE
     * @param s      settings
     * @return decision
     */
    public Decision check(String tenant, boolean write, ThrottleSettings s) {
        State st = states.get(tenant);
        if (st == null) {
            return allow(1.0);
        }
        long now = nanoClock.getAsLong();
        synchronized (st) {
            update(st, now, s);
            if (s.isFixed()) {
                if (now >= st.fixedUntil) {
                    return allow(1.0);
                }
                if (!s.isFixedBlockWrites()) {
                    return allow(s.getFixedRateFactor());        // Lab2-like: capped rate for the penalty
                }
                if (write) {
                    return new Decision(429, "throttled_fixed", "writes blocked for " + s.getFixedPenaltySeconds()
                            + " s after a violation (fixed policy)", ceilSeconds(st.fixedUntil - now), 1.0);
                }
                return allow(1.0);
            }
            if (st.level >= 3) {
                double target = s.getLevel3Threshold() / 2;
                long wait = (long) Math.ceil(s.getScoreHalfLifeSeconds()
                        * Math.log(st.score / target) / Math.log(2));
                return new Decision(403, "throttled_blocked", String.format("tenant blocked (score %.1f >= level-3; "
                        + "unblocked when it decays below %.1f, or by an admin)", st.score, target),
                        Math.max(1, wait), s.getRestrictedRateFactor());
            }
            if (st.level == 2 && write && now < st.cooldownUntil) {
                return new Decision(429, "throttled_cooldown", String.format("writes paused: cooldown (score %.1f)",
                        st.score), ceilSeconds(st.cooldownUntil - now), s.getRestrictedRateFactor());
            }
            return allow(st.level >= 1 ? s.getRestrictedRateFactor() : 1.0);
        }
    }

    /**
     * Current state of a tenant (after decay).
     *
     * @param tenant tenant id
     * @param s      settings
     * @return status
     */
    public Status status(String tenant, ThrottleSettings s) {
        State st = states.get(tenant);
        if (st == null) {
            return new Status(0, 0, 0, 0, 0, ImmutableList.of());
        }
        long now = nanoClock.getAsLong();
        synchronized (st) {
            update(st, now, s);
            return new Status(st.score, st.level, st.level == 2 ? ceilSeconds(st.cooldownUntil - now) : 0,
                    st.cooldownEntries, ceilSeconds(st.fixedUntil - now), ImmutableList.copyOf(st.history));
        }
    }

    /**
     * Admin unblock: score, level, cooldown and fixed penalty are reset (history kept).
     *
     * @param tenant tenant id
     */
    public void unblock(String tenant) {
        State st = states.get(tenant);
        if (st == null) {
            return;
        }
        synchronized (st) {
            st.score = 0;
            st.level = 0;
            st.cooldownUntil = 0;
            st.cooldownEntries = 0;
            st.fixedUntil = 0;
            st.last = nanoClock.getAsLong();
        }
    }

    private void update(State st, long now, ThrottleSettings s) {
        double dt = Math.max(0, now - st.last) / NANOS_PER_SECOND;
        if (dt > 0 && s.getScoreHalfLifeSeconds() > 0) {
            st.score *= Math.pow(0.5, dt / s.getScoreHalfLifeSeconds());
        }
        st.last = Math.max(st.last, now);
        if (s.isFixed()) {
            return;
        }
        int target = st.score >= s.getLevel3Threshold() ? 3
                : st.score >= s.getLevel2Threshold() ? 2 : st.score >= s.getLevel1Threshold() ? 1 : 0;
        if (target > st.level) {
            if (st.level < 2 && target >= 2) {             // entering cooldown from below
                st.cooldownEntries++;
                long cd = Math.min((long) s.getCooldownSeconds() << Math.min(st.cooldownEntries - 1, 20),
                        s.getCooldownMaxSeconds());
                st.cooldownUntil = now + seconds((int) cd);
            }
            st.level = target;
        }
        while (st.level > 0 && st.score < s.threshold(st.level) / 2) {   // hysteresis
            st.level--;
        }
        if (st.level == 0) {
            st.cooldownEntries = 0;
        }
    }

    private static Decision allow(double factor) {
        return new Decision(0, null, null, 0, factor);
    }

    private static long seconds(int s) {
        return s * (long) NANOS_PER_SECOND;
    }

    private static long ceilSeconds(long nanos) {
        return nanos <= 0 ? 0 : (long) Math.ceil(nanos / NANOS_PER_SECOND);
    }
}
