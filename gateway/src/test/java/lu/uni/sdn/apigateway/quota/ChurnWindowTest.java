package lu.uni.sdn.apigateway.quota;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Sliding churn window with caller-supplied times.
 */
public class ChurnWindowTest {

    private static final long SEC = 1_000_000_000L;
    private static final long WIN = 60 * SEC;

    @Test
    public void countsInsideWindowOnly() {
        ChurnWindow w = new ChurnWindow();
        w.record(0, 10);
        w.record(30 * SEC, 5);
        assertEquals(15, w.count(59 * SEC, WIN));
        assertEquals(5, w.count(60 * SEC, WIN));      // first event exactly one window old: expired
        assertEquals(0, w.count(91 * SEC, WIN));
    }

    @Test
    public void zeroOpsNotRecorded() {
        ChurnWindow w = new ChurnWindow();
        w.record(0, 0);
        assertEquals(0, w.count(1, WIN));
    }

    @Test
    public void secondsUntilRoom() {
        ChurnWindow w = new ChurnWindow();
        w.record(0, 150);
        w.record(20 * SEC, 50);                        // 200 in window
        assertEquals(0, w.secondsUntilRoom(30 * SEC, WIN, 250, 50));
        // limit 200: +1 needs the 150 at t=0 to expire -> at t=60 -> 30 s from t=30
        assertEquals(30, w.secondsUntilRoom(30 * SEC, WIN, 200, 1));
        // +160 needs both to expire -> t=80 -> 50 s
        assertEquals(50, w.secondsUntilRoom(30 * SEC, WIN, 200, 160));
        // n alone above the limit: never fits; answer is the window length
        assertEquals(60, w.secondsUntilRoom(30 * SEC, WIN, 200, 201));
    }
}
