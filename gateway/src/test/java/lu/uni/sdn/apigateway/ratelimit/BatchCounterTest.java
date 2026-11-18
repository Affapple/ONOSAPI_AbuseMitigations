package lu.uni.sdn.apigateway.ratelimit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

/**
 * Streaming count of the top-level "flows" array.
 */
public class BatchCounterTest {

    private static int count(String s) {
        return BatchCounter.countFlows(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void countsTopLevelElementsOnly() {
        assertEquals(0, count("{\"flows\":[]}"));
        assertEquals(1, count("{\"flows\":[{\"a\":1}]}"));
        assertEquals(3, count("{\"x\":{\"flows\":[1,2,3,4,5]},\"flows\":[{\"treatment\":{\"instructions\":"
                + "[{\"type\":\"OUTPUT\"},{\"type\":\"OUTPUT\"}]}},{},{\"flows\":[1,2]}]}"));
        assertEquals(2, count("{\"flows\":[1,\"x\"],\"after\":true}"));
    }

    @Test
    public void notABatchGivesMinusOne() {
        assertEquals(-1, count(""));
        assertEquals(-1, count("[]"));
        assertEquals(-1, count("{}"));
        assertEquals(-1, count("{\"flows\":{}}"));
        assertEquals(-1, count("{\"flows\":[1,2"));
        assertEquals(-1, count("not json"));
    }

    /** Auditor's bypass: Jackson's tree keeps the LAST "flows", the old counter counted the FIRST. */
    @Test
    public void duplicateKeysAreUncountable() {
        assertEquals(-1, count("{\"flows\":[{}],\"flows\":[{},{},{},{},{},{}]}"));
        assertEquals(-1, count("{\"x\":1,\"x\":2,\"flows\":[{}]}"));
        assertEquals(-1, count("{\"flows\":[{\"deviceId\":\"a\",\"deviceId\":\"b\"}]}"));
    }
}
