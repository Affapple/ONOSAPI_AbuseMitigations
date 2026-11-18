package lu.uni.sdn.apigateway.acl;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Path tricks that could make the ACL and Jersey disagree must be rejected.
 */
public class PathSanitizerTest {

    @Test
    public void decodesNormalPaths() {
        assertEquals(ImmutableList.of(), PathSanitizer.segments(""));
        assertEquals(ImmutableList.of(), PathSanitizer.segments("/"));
        assertEquals(ImmutableList.of("whoami"), PathSanitizer.segments("whoami"));
        assertEquals(ImmutableList.of("whoami"), PathSanitizer.segments("/whoami"));
        assertEquals(ImmutableList.of("v1", "devices", "of:0000000000000002", "ports"),
                PathSanitizer.segments("v1/devices/of%3A0000000000000002/ports"));
        assertEquals(ImmutableList.of("intents", "a b"), PathSanitizer.segments("intents/a%20b"));
    }

    @Test
    public void rejectsAmbiguousPaths() {
        String[] bad = {
            "flows/", "//flows", "v1//devices", "v1/./devices", "v1/../flows", "v1/%2e%2e/flows",
            "v1/devices%2F..%2Fflows", "v1/devices%2fx", "v1/devices%5Cx", "flows;x=1", "v1/devices;a/x",
            "v1/%", "v1/%zz", "v1/%0a", "v1/%00",
        };
        for (String p : bad) {
            try {
                PathSanitizer.segments(p);
                fail("accepted " + p);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }
}
