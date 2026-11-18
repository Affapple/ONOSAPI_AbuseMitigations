package lu.uni.sdn.apigateway.audit;

import com.google.common.collect.ImmutableSet;
import org.junit.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B7 classification, de-duplication and bounds; and the rejection counters.
 */
public class BypassAuditorTest {

    private static final Predicate<String> TENANT = n -> n.startsWith("lu.uni.sdn.gw.");
    private static final Set<String> ALLOWED = ImmutableSet.of("org.onosproject.core");
    private static final LongPredicate TENANT_INTENT_FLOWS = id -> id == 777;

    private final BypassAuditor a = new BypassAuditor();

    private String flow(long id, String app) {
        return a.classifyFlow(id, app, TENANT, TENANT_INTENT_FLOWS, ALLOWED).orElse("ok");
    }

    private String intent(long id, String app) {
        return a.classifyIntent(id, app, TENANT, ALLOWED).orElse("ok");
    }

    @Test
    public void flows() {
        a.markGatewayFlow(1);
        assertEquals("ok", flow(1, "lu.uni.sdn.gw.t1"));                                  // via the gateway
        assertEquals("tenant appId, not created through the gateway", flow(2, "lu.uni.sdn.gw.t1")); // V3 spoof
        assertEquals("ok", flow(777, BypassAuditor.INTENT_APP));                          // tenant intent's rule
        assertEquals("intent-compiled flow not from a tenant intent", flow(778, BypassAuditor.INTENT_APP));
        assertEquals("ok", flow(5, "org.onosproject.core"));                              // allowlisted
        assertEquals("appId is not a tenant, intent or allowlisted app", flow(6, "org.onosproject.rest"));
        assertEquals("appId is not a tenant, intent or allowlisted app", flow(7, "lu.uni.sdn.api_gateway"));
    }

    @Test
    public void intents() {
        a.markGatewayIntent(10);
        assertEquals("ok", intent(10, "lu.uni.sdn.gw.t1"));
        assertEquals("tenant appId, not submitted through the gateway", intent(11, "lu.uni.sdn.gw.t1"));  // V7
        assertEquals("ok", intent(12, "org.onosproject.core"));
        assertEquals("appId is not a tenant or allowlisted app", intent(13, "org.onosproject.cli"));
    }

    @Test
    public void reportDeduplicatesAndCounts() {
        BypassAuditor.Bypass b = new BypassAuditor.Bypass(1L, "flow", "of:2", "org.onosproject.rest", "6", "x");
        assertTrue(a.report(b));
        assertFalse("same flow seen again (ADD_REQUESTED then ADDED)", a.report(b));
        assertTrue(a.report(new BypassAuditor.Bypass(2L, "intent", "-", "lu.uni.sdn.gw.t1", "k", "y")));
        BypassAuditor.Snapshot s = a.snapshot();
        assertEquals(2, s.getTotal());
        assertEquals(Long.valueOf(1), s.getByKind().get("flow"));
        assertEquals(Long.valueOf(1), s.getByAppId().get("org.onosproject.rest"));
        assertEquals(2, s.getRecent().size());
        a.reset();
        assertEquals(0, a.snapshot().getTotal());
        assertTrue("reset also clears the de-dup set", a.report(b));
    }

    @Test
    public void recentIsBounded() {
        for (int i = 0; i < 3 * BypassAuditor.RECENT; i++) {
            a.report(new BypassAuditor.Bypass(i, "flow", "of:2", "x", Integer.toString(i), "y"));
        }
        BypassAuditor.Snapshot s = a.snapshot();
        assertEquals(3 * BypassAuditor.RECENT, s.getTotal());
        assertEquals(BypassAuditor.RECENT, s.getRecent().size());
        assertEquals(Integer.toString(3 * BypassAuditor.RECENT - 1), s.getRecent().get(BypassAuditor.RECENT - 1)
                .getId());
    }

    @Test
    public void flowMarksAreLruBounded() {
        for (long i = 0; i < BypassAuditor.MAX_FLOW_MARKS + 10; i++) {
            a.markGatewayFlow(i);
        }
        assertEquals(BypassAuditor.MAX_FLOW_MARKS, a.snapshot().getTrackedGatewayFlows());
        assertEquals("oldest evicted", Optional.of("tenant appId, not created through the gateway"),
                a.classifyFlow(0, "lu.uni.sdn.gw.t1", TENANT, TENANT_INTENT_FLOWS, ALLOWED));
    }

    @Test
    public void allowlistParsing() {
        assertEquals(ImmutableSet.of("org.onosproject.core", "org.onosproject.fwd"),
                BypassAuditor.parseAllowlist(" org.onosproject.core, ,org.onosproject.fwd "));
        assertTrue(BypassAuditor.parseAllowlist("").isEmpty());
    }

    @Test
    public void rejectionCountersPerTenantModuleReason() {
        RejectionCounters c = new RejectionCounters();
        c.count("t1", "bad_priority");
        c.count("t1", "bad_priority");
        c.count("t1", "dst_capture");
        c.count("t1", "throttled_blocked");
        c.count(null, "unknown_account");
        Map<String, Map<String, Map<String, Long>>> s = c.snapshot();
        assertEquals(Long.valueOf(2), s.get("t1").get("validation").get("bad_priority"));
        assertEquals(Long.valueOf(1), s.get("t1").get("conflict").get("dst_capture"));
        assertEquals(Long.valueOf(1), s.get("t1").get("throttle").get("throttled_blocked"));
        assertEquals(Long.valueOf(1), s.get(RejectionCounters.OTHER).get("acl").get("unknown_account"));
        assertEquals("other", RejectionCounters.module("not_found"));
        c.reset();
        assertTrue(c.snapshot().isEmpty());
    }
}
