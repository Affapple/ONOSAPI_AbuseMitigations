package lu.uni.sdn.apigateway.ratelimit;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Token bucket behaviour with an injected clock (no sleeps).
 */
public class TokenBucketLimiterTest {

    private static final long SEC = 1_000_000_000L;
    private final AtomicLong now = new AtomicLong(1000 * SEC);
    private final TokenBucketLimiter lim = new TokenBucketLimiter(now::get);

    private boolean take(String key, int cost) {
        return lim.tryAcquire(key, cost, 10.0, 20).isAllowed();
    }

    @Test
    public void burstThenDenied() {
        for (int i = 0; i < 20; i++) {
            assertTrue("request " + i, take("t1", 1));
        }
        TokenBucketLimiter.Result r = lim.tryAcquire("t1", 1, 10.0, 20);
        assertFalse(r.isAllowed());
        assertEquals(1, r.getRetryAfterSeconds());
    }

    @Test
    public void refillsAtRate() {
        for (int i = 0; i < 20; i++) {
            take("t1", 1);
        }
        assertFalse(take("t1", 1));
        now.addAndGet(SEC / 10);            // 0.1 s -> 1 token at 10/s
        assertTrue(take("t1", 1));
        assertFalse(take("t1", 1));
        now.addAndGet(100 * SEC);           // long idle: capped at burst
        for (int i = 0; i < 20; i++) {
            assertTrue(take("t1", 1));
        }
        assertFalse(take("t1", 1));
    }

    @Test
    public void rejectedRequestsTakeNoTokens() {
        for (int i = 0; i < 20; i++) {
            take("t1", 1);
        }
        for (int i = 0; i < 100; i++) {
            assertFalse(take("t1", 1));
        }
        now.addAndGet(SEC / 10);
        assertTrue(take("t1", 1));          // still exactly one token after 0.1 s
    }

    @Test
    public void tenantsAreIndependent() {
        for (int i = 0; i < 20; i++) {
            take("tenant:t1", 1);
        }
        assertFalse(take("tenant:t1", 1));
        assertTrue(take("tenant:t2", 1));
    }

    @Test
    public void weightedCostAndBatchLargerThanBurstGoesIntoDebt() {
        assertTrue(take("t1", 15));         // 20 -> 5
        assertFalse(take("t1", 6));         // needs 6
        assertTrue(take("t1", 5));          // 5 -> 0
        now.addAndGet(10 * SEC);            // refill to 20 (cap)
        TokenBucketLimiter.Result big = lim.tryAcquire("t1", 50, 10.0, 20);
        assertTrue(big.isAllowed());        // full bucket admits a 50-flow batch...
        assertEquals(-30.0, big.getTokens(), 1e-9);  // ...but pays all 50
        TokenBucketLimiter.Result next = lim.tryAcquire("t1", 1, 10.0, 20);
        assertFalse(next.isAllowed());
        assertEquals(4, next.getRetryAfterSeconds());  // (1 - (-30)) / 10 = 3.1 -> 4
        now.addAndGet(3100 * 1_000_000L);  // 3.1 s
        assertTrue(take("t1", 1));
    }

    @Test
    public void retryAfterForLargeRequest() {
        assertTrue(take("t1", 20));         // empty
        TokenBucketLimiter.Result r = lim.tryAcquire("t1", 10, 2.0, 20);
        assertFalse(r.isAllowed());
        assertEquals(5, r.getRetryAfterSeconds());   // 10 tokens at 2/s
    }

    @Test
    public void configChangeAppliesImmediately() {
        for (int i = 0; i < 20; i++) {
            take("t1", 1);
        }
        now.addAndGet(10 * SEC);            // 10 s at the new rate 1/s = 10 tokens...
        // ...but with the new burst 3 the bucket can only hold 3
        assertTrue(lim.tryAcquire("t1", 1, 1.0, 3).isAllowed());
        assertTrue(lim.tryAcquire("t1", 1, 1.0, 3).isAllowed());
        assertTrue(lim.tryAcquire("t1", 1, 1.0, 3).isAllowed());
        assertFalse(lim.tryAcquire("t1", 1, 1.0, 3).isAllowed());
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidParametersRejected() {
        lim.tryAcquire("t1", 1, 0.0, 20);
    }

    @Test
    public void resetRefillsEverything() {
        take("t1", 20);
        lim.reset();
        assertTrue(lim.tokens().isEmpty());
        assertTrue(take("t1", 20));
    }

    /** A clock read that goes backwards (skew between threads) must not refill twice. */
    @Test
    public void backwardsClockDoesNotRefillTwice() {
        for (int i = 0; i < 20; i++) {
            take("t1", 1);
        }
        now.addAndGet(SEC / 10);                   // +1 token
        assertTrue(take("t1", 1));
        now.addAndGet(-SEC / 20);                  // skewed earlier read
        assertFalse(take("t1", 1));
        now.addAndGet(SEC / 20);                   // back to the same instant: still nothing new
        assertFalse(take("t1", 1));
        now.addAndGet(SEC / 10);
        assertTrue(take("t1", 1));
    }
}
