package lu.uni.sdn.apigateway.throttle;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B6 with an injected clock (no sleeps), default settings: half-life 60 s, thresholds
 * 10/25/50, rate factor 0.5, cooldown 30 s (cap 960), fixed penalty 60 s, weights 1/2/3/5/3.
 */
public class ThrottleManagerTest {

    private static final long SEC = 1_000_000_000L;
    private static final double EPS = 1e-6;
    private static final ThrottleSettings ADAPTIVE = settings(ThrottleSettings.ADAPTIVE);
    private static final ThrottleSettings FIXED = settings(ThrottleSettings.FIXED);

    private final AtomicLong now = new AtomicLong(1000 * SEC);
    private final ThrottleManager tm = new ThrottleManager(now::get);

    private static ThrottleSettings settings(String mode) {
        return new ThrottleSettings(mode, 60, 10, 25, 50, 0.5, 30, 960, 60, ThrottleSettings.EFFECT_BLOCK_WRITES,
                0.1, 1, 2, 3, 5, 3);
    }

    private void add(double w) {
        tm.record("t1", "test", w, ADAPTIVE);
    }

    private ThrottleManager.Status st() {
        return tm.status("t1", ADAPTIVE);
    }

    private void advance(double seconds) {
        now.addAndGet((long) (seconds * SEC));
    }

    @Test
    public void weightsPerReasonAndOwnRejectionsWeighNothing() {
        assertEquals(1, ThrottleManager.weightFor("rate_limited", false, ADAPTIVE), EPS);
        assertEquals(1, ThrottleManager.weightFor("batch_too_large", false, ADAPTIVE), EPS);
        assertEquals(2, ThrottleManager.weightFor("quota_churn", false, ADAPTIVE), EPS);
        assertEquals(3, ThrottleManager.weightFor("match_not_owned", false, ADAPTIVE), EPS);
        assertEquals(3, ThrottleManager.weightFor("missing_prerequisite", false, ADAPTIVE), EPS);
        assertEquals(5, ThrottleManager.weightFor("dst_capture", false, ADAPTIVE), EPS);
        assertEquals(5, ThrottleManager.weightFor("compiled_conflict", false, ADAPTIVE), EPS);
        assertEquals(3, ThrottleManager.weightFor("port_not_owned", false, ADAPTIVE), EPS);
        assertEquals(0, ThrottleManager.weightFor("throttled_blocked", false, ADAPTIVE), EPS);
        assertEquals(0, ThrottleManager.weightFor("throttled_cooldown", false, ADAPTIVE), EPS);
        assertEquals(0, ThrottleManager.weightFor("throttled_fixed", false, ADAPTIVE), EPS);
        assertEquals(0, ThrottleManager.weightFor("not_found", false, ADAPTIVE), EPS);
        assertEquals(0, ThrottleManager.weightFor("bad_request", false, ADAPTIVE), EPS);
        add(0);
        assertEquals(0, st().getScore(), EPS);
    }

    @Test
    public void exponentialDecay() {
        add(10);
        advance(60);
        assertEquals(5, st().getScore(), 1e-9);
        advance(60);
        assertEquals(2.5, st().getScore(), 1e-9);
    }

    @Test
    public void everyThresholdUp() {
        add(9.9);
        assertEquals(0, st().getLevel());
        assertEquals(1.0, tm.check("t1", true, ADAPTIVE).getRateFactor(), EPS);
        add(0.1);
        assertEquals(1, st().getLevel());
        ThrottleManager.Decision d = tm.check("t1", true, ADAPTIVE);
        assertTrue(d.isAllowed());
        assertEquals(0.5, d.getRateFactor(), EPS);                      // restricted: half the refill rate
        add(15);
        assertEquals(2, st().getLevel());
        d = tm.check("t1", true, ADAPTIVE);
        assertEquals(429, d.getStatus());
        assertEquals("throttled_cooldown", d.getReason());
        assertEquals(30, d.getRetryAfterSeconds());
        assertTrue("reads continue during cooldown", tm.check("t1", false, ADAPTIVE).isAllowed());
        add(25);
        assertEquals(3, st().getLevel());
        d = tm.check("t1", false, ADAPTIVE);
        assertEquals(403, d.getStatus());
        assertEquals("throttled_blocked", d.getReason());
        assertEquals(60, d.getRetryAfterSeconds());                    // 50 -> 25 is one half-life
    }

    @Test
    public void everyThresholdDownWithHysteresis() {
        add(50);
        assertEquals(3, st().getLevel());
        advance(60 * Math.log(50 / 25.5) / Math.log(2));               // score 25.5: still blocked
        assertEquals(3, st().getLevel());
        advance(60 * Math.log(25.5 / 24.9) / Math.log(2));             // 24.9 < 50/2
        assertEquals(2, st().getLevel());
        advance(60 * Math.log(24.9 / 12.6) / Math.log(2));             // 12.6: still level 2
        assertEquals(2, st().getLevel());
        advance(60 * Math.log(12.6 / 12.4) / Math.log(2));             // 12.4 < 25/2
        assertEquals(1, st().getLevel());
        advance(60 * Math.log(12.4 / 5.1) / Math.log(2));
        assertEquals(1, st().getLevel());
        advance(60 * Math.log(5.1 / 4.9) / Math.log(2));               // 4.9 < 10/2
        assertEquals(0, st().getLevel());
    }

    @Test
    public void noFlappingAroundAThreshold() {
        add(10);
        assertEquals(1, st().getLevel());
        for (int i = 0; i < 5; i++) {
            advance(60 * Math.log(10 / 9.0) / Math.log(2));            // decays to ~9 (below 10)
            assertEquals("stays restricted until below 5", 1, st().getLevel());
            add(10 - st().getScore());                                  // back to 10
        }
    }

    @Test
    public void cooldownDoublesOnReentryAndResetsAtLevelZero() {
        add(25);
        assertEquals(30, st().getCooldownRemainingSeconds());
        advance(60 * Math.log(25 / 12.0) / Math.log(2));               // down to level 1
        assertEquals(1, st().getLevel());
        add(25 - st().getScore());                                      // re-enter level 2
        assertEquals(2, st().getLevel());
        assertEquals(60, st().getCooldownRemainingSeconds());
        assertEquals(2, st().getCooldownEntries());
        advance(60 * Math.log(25 / 12.0) / Math.log(2));
        add(25 - st().getScore());
        assertEquals(120, st().getCooldownRemainingSeconds());
        advance(600);                                                   // decays to level 0
        assertEquals(0, st().getLevel());
        add(25);
        assertEquals("doubling reset after level 0", 30, st().getCooldownRemainingSeconds());
    }

    @Test
    public void cooldownCapped() {
        ThrottleSettings capped = new ThrottleSettings(ThrottleSettings.ADAPTIVE, 60, 10, 25, 50, 0.5, 30, 45, 60,
                ThrottleSettings.EFFECT_BLOCK_WRITES, 0.1, 1, 2, 3, 5, 3);
        tm.record("t1", "x", 25, capped);
        advance(60 * Math.log(25 / 12.0) / Math.log(2));
        tm.record("t1", "x", 25 - tm.status("t1", capped).getScore(), capped);
        assertEquals(45, tm.status("t1", capped).getCooldownRemainingSeconds());
    }

    @Test
    public void afterCooldownWritesResumeButStayRestricted() {
        add(25);
        advance(31);
        ThrottleManager.Decision d = tm.check("t1", true, ADAPTIVE);
        assertTrue(d.isAllowed());
        assertEquals(0.5, d.getRateFactor(), EPS);
    }

    @Test
    public void decayingFromBlockedIntoCooldownStartsNoNewCooldown() {
        add(50);                                                        // entered level 2 on the way: 1 entry
        advance(61);                                                    // ~24.6: level 2, old cooldown over
        assertEquals(2, st().getLevel());
        assertEquals(1, st().getCooldownEntries());
        assertTrue(tm.check("t1", true, ADAPTIVE).isAllowed());
    }

    @Test
    public void adminUnblock() {
        add(80);
        assertEquals(403, tm.check("t1", true, ADAPTIVE).getStatus());
        tm.unblock("t1");
        assertTrue(tm.check("t1", true, ADAPTIVE).isAllowed());
        assertEquals(0, st().getLevel());
        assertEquals(0, st().getScore(), EPS);
        assertEquals("history kept", 1, st().getHistory().size());
    }

    @Test
    public void otherTenantsUnaffected() {
        add(80);
        assertTrue(tm.check("t2", true, ADAPTIVE).isAllowed());
        assertEquals(1.0, tm.check("t2", false, ADAPTIVE).getRateFactor(), EPS);
    }

    /** Audit B6 fix 1: decoder fuzzing on write paths is scored; 404s stay free. */
    @Test
    public void formatErrorsOnFlowWritesAreValidationViolations() {
        assertEquals(3, ThrottleManager.weightFor("bad_request", true, ADAPTIVE), EPS);
        assertEquals(0, ThrottleManager.weightFor("bad_request", false, ADAPTIVE), EPS);
        assertEquals(0, ThrottleManager.weightFor("not_found", true, ADAPTIVE), EPS);
        assertEquals(3, ThrottleManager.weightFor("bad_priority", true, ADAPTIVE), EPS);
    }

    /** Audit B6 fix 2: fixed mode with effect "rate" = Lab2's fixed-duration rate cap. */
    @Test
    public void fixedModeRateEffect() {
        ThrottleSettings rate = new ThrottleSettings(ThrottleSettings.FIXED, 60, 10, 25, 50, 0.5, 30, 960, 60,
                ThrottleSettings.EFFECT_RATE, 0.1, 1, 2, 3, 5, 3);
        assertEquals(1.0, tm.check("t1", true, rate).getRateFactor(), EPS);
        tm.record("t1", "match_not_owned", 3, rate);
        for (boolean write : new boolean[]{true, false}) {
            ThrottleManager.Decision d = tm.check("t1", write, rate);
            assertTrue("never refused, only slowed", d.isAllowed());
            assertEquals(0.1, d.getRateFactor(), EPS);
        }
        for (int i = 0; i < 30; i++) {
            tm.record("t1", "dst_capture", 5, rate);
        }
        assertTrue("no escalation", tm.check("t1", false, rate).isAllowed());
        assertEquals(0, tm.status("t1", rate).getLevel());
        advance(61);
        assertEquals(1.0, tm.check("t1", true, rate).getRateFactor(), EPS);
    }

    @Test
    public void fixedModeIsLab2() {
        tm.record("t1", "match_not_owned", 3, FIXED);
        ThrottleManager.Decision d = tm.check("t1", true, FIXED);
        assertEquals(429, d.getStatus());
        assertEquals("throttled_fixed", d.getReason());
        assertEquals(60, d.getRetryAfterSeconds());
        assertTrue("reads untouched", tm.check("t1", false, FIXED).isAllowed());
        for (int i = 0; i < 30; i++) {
            tm.record("t1", "dst_capture", 5, FIXED);                    // way above every threshold
        }
        assertEquals("no escalation: never blocked", 429, tm.check("t1", true, FIXED).getStatus());
        assertTrue(tm.check("t1", false, FIXED).isAllowed());
        assertEquals(1.0, tm.check("t1", false, FIXED).getRateFactor(), EPS);
        assertEquals(0, tm.status("t1", FIXED).getLevel());
        advance(59);
        assertFalse(tm.check("t1", true, FIXED).isAllowed());
        advance(2);
        assertTrue("fixed 60 s, then free", tm.check("t1", true, FIXED).isAllowed());
    }

    /** Audit B7 fix 3: record() reports when a fixed penalty starts (the caller scales the B2 tokens). */
    @Test
    public void recordReportsTheStartOfAFixedPenalty() {
        assertTrue("first violation starts the penalty", tm.record("t1", "match_not_owned", 3, FIXED));
        assertFalse("further violations extend it", tm.record("t1", "match_not_owned", 3, FIXED));
        assertFalse("zero weight is ignored", tm.record("t2", "match_not_owned", 0, FIXED));
        advance(61);
        assertTrue("a new penalty after the old one expired", tm.record("t1", "match_not_owned", 3, FIXED));
        assertFalse("adaptive mode never reports one", tm.record("t3", "match_not_owned", 3, ADAPTIVE));
    }

    @Test
    public void historyBounded() {
        for (int i = 0; i < 50; i++) {
            add(0.01);
        }
        assertEquals(ThrottleManager.HISTORY, st().getHistory().size());
    }
}
