package lu.uni.sdn.apigateway.ratelimit;

import com.google.common.collect.ImmutableMap;
import lombok.Value;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * One token bucket per key (tenant). A bucket holds at most {@code burst} tokens
 * and refills at {@code rps} tokens per second. Rate and burst are passed on every
 * call, so a {@code cfg set} takes effect on the next request.
 *
 * <p>Weighted cost: a request of cost c is admitted when the bucket holds at least
 * min(c, burst) tokens, and then c tokens are taken. A batch larger than the burst
 * is therefore possible from a full bucket but drives the bucket negative, so the
 * tenant pays for every flow in it before the next request is admitted. Rejected
 * requests take no tokens.</p>
 *
 * <p>The clock is injectable (nanoseconds) so tests need no sleeps.</p>
 */
public final class TokenBucketLimiter {

    private static final double NANOS_PER_SECOND = 1e9;

    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Outcome of {@link #tryAcquire}. */
    @Value
    public static class Result {
        boolean allowed;
        /** Whole seconds until the request could be admitted (0 when allowed). */
        long retryAfterSeconds;
        /** Tokens left after the call (may be negative after a large batch). */
        double tokens;
    }

    private static final class Bucket {
        double tokens;
        long last;

        Bucket(double tokens, long last) {
            this.tokens = tokens;
            this.last = last;
        }
    }

    /**
     * Creates a limiter using {@link System#nanoTime()}.
     */
    public TokenBucketLimiter() {
        this(System::nanoTime);
    }

    /**
     * Creates a limiter with a custom clock.
     *
     * @param nanoClock monotonic clock in nanoseconds
     */
    public TokenBucketLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /**
     * Tries to take {@code cost} tokens from {@code key}'s bucket.
     *
     * @param key   bucket key (e.g. "tenant:t1")
     * @param cost  request cost, at least 1
     * @param rps   refill rate in tokens/s, must be &gt; 0
     * @param burst bucket capacity, at least 1
     * @return result
     */
    public Result tryAcquire(String key, int cost, double rps, int burst) {
        if (cost < 1 || rps <= 0 || burst < 1) {
            throw new IllegalArgumentException("cost>=1, rps>0, burst>=1 required");
        }
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(burst, nanoClock.getAsLong()));
        synchronized (b) {
            // Clock read inside the lock and 'last' never moves backwards, so two
            // threads with skewed reads cannot refill the same interval twice.
            long now = nanoClock.getAsLong();
            double elapsed = Math.max(0, now - b.last) / NANOS_PER_SECOND;
            b.tokens = Math.min(burst, b.tokens + elapsed * rps);
            b.last = Math.max(b.last, now);
            double need = Math.min(cost, burst);
            if (b.tokens >= need) {
                b.tokens -= cost;
                return new Result(true, 0, b.tokens);
            }
            long retry = (long) Math.ceil((need - b.tokens) / rps);
            return new Result(false, Math.max(1, retry), b.tokens);
        }
    }

    /**
     * Current token counts (without refilling); for tests.
     *
     * @return key to tokens
     */
    Map<String, Double> tokens() {
        ImmutableMap.Builder<String, Double> m = ImmutableMap.builder();
        buckets.forEach((k, b) -> {
            synchronized (b) {
                m.put(k, b.tokens);
            }
        });
        return m.build();
    }

    /**
     * Bucket key of a tenant (shared by the B2 filter and the B6 fixed policy).
     *
     * @param tenantId tenant id
     * @return key
     */
    public static String tenantKey(String tenantId) {
        return "tenant:" + tenantId;
    }

    /**
     * Scales a bucket's current tokens (B6 fixed policy "rate": the cap applies
     * immediately, not only to the refill). A missing bucket is left alone.
     *
     * @param key    bucket key
     * @param factor factor in (0, 1]
     */
    public void scale(String key, double factor) {
        Bucket b = buckets.get(key);
        if (b != null) {
            synchronized (b) {
                if (b.tokens > 0) {
                    b.tokens *= factor;
                }
            }
        }
    }

    /** Forgets all buckets (they restart full); for tests. */
    void reset() {
        buckets.clear();
    }
}
