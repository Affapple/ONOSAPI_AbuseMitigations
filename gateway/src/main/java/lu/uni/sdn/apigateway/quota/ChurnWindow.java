package lu.uni.sdn.apigateway.quota;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window counter of write operations (flow adds + deletes, intent submits +
 * withdraws) for one tenant. Times are caller-supplied nanoseconds, so tests need
 * no clock. Thread-safe.
 */
public final class ChurnWindow {

    private static final double NANOS_PER_SECOND = 1e9;

    /** (timestamp nanos, operations). Oldest first. */
    private final Deque<long[]> events = new ArrayDeque<>();

    /**
     * Operations inside the window ending at {@code now}.
     *
     * @param now         current time, nanos
     * @param windowNanos window length, nanos
     * @return operation count
     */
    public synchronized int count(long now, long windowNanos) {
        prune(now, windowNanos);
        long sum = 0;
        for (long[] e : events) {
            sum += e[1];
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    /**
     * Records {@code n} operations at {@code now}.
     *
     * @param now current time, nanos
     * @param n   operations
     */
    public synchronized void record(long now, int n) {
        if (n > 0) {
            events.addLast(new long[]{now, n});
        }
    }

    /**
     * Whole seconds until {@code n} more operations would fit under {@code limit}
     * (0 if they fit now). If n alone exceeds the limit, returns the window length.
     *
     * @param now         current time, nanos
     * @param windowNanos window length, nanos
     * @param limit       churn limit
     * @param n           operations wanted
     * @return seconds to wait
     */
    public synchronized long secondsUntilRoom(long now, long windowNanos, int limit, int n) {
        long excess = (long) count(now, windowNanos) + n - limit;
        if (excess <= 0) {
            return 0;
        }
        for (long[] e : events) {
            excess -= e[1];
            if (excess <= 0) {
                return Math.max(1, (long) Math.ceil((e[0] + windowNanos - now) / NANOS_PER_SECOND));
            }
        }
        return Math.max(1, (long) Math.ceil(windowNanos / NANOS_PER_SECOND));
    }

    private void prune(long now, long windowNanos) {
        while (!events.isEmpty() && events.peekFirst()[0] <= now - windowNanos) {
            events.removeFirst();
        }
    }
}
