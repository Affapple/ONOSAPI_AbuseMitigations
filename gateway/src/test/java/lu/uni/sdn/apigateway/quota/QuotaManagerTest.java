package lu.uni.sdn.apigateway.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.DefaultApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.HostId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultFlowEntry;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleServiceAdapter;
import org.onosproject.net.intent.FlowRuleIntent;
import org.onosproject.net.intent.HostToHostIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentServiceAdapter;
import org.onosproject.net.intent.IntentState;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.MockIdGenerator;
import org.onosproject.net.intent.PathIntent;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Quota counting with fake Flow/Intent services: direct + intent-compiled flows,
 * non-counted intent states, the pending ledger (store lag) and the checks.
 */
public class QuotaManagerTest {

    private static final long SEC = 1_000_000_000L;
    private static final DeviceId S1 = DeviceId.deviceId("of:0000000000000001");
    private static final DeviceId S2 = DeviceId.deviceId("of:0000000000000002");
    private static final DeviceId S3 = DeviceId.deviceId("of:0000000000000003");
    private static final ApplicationId T1 = new DefaultApplicationId(179, "lu.uni.sdn.gw.t1");
    private static final ApplicationId T2 = new DefaultApplicationId(180, "lu.uni.sdn.gw.t2");
    private static final ApplicationId INTENT_APP = new DefaultApplicationId(5, "org.onosproject.net.intent");

    private final AtomicLong now = new AtomicLong(1000 * SEC);
    private final List<FlowEntry> store = new ArrayList<>();
    private final Map<Key, Intent> intents = new HashMap<>();
    private final Map<Key, IntentState> states = new HashMap<>();
    private final Map<Key, List<Intent>> installables = new HashMap<>();
    private QuotaManager qm;
    private int prio = 40000;

    @Before
    public void setUp() {
        MockIdGenerator.cleanBind();
        FlowRuleServiceAdapter flows = new FlowRuleServiceAdapter() {
            @Override
            public Iterable<FlowEntry> getFlowEntriesById(ApplicationId id) {
                return store.stream().filter(e -> e.appId() == id.id()).collect(Collectors.toList());
            }
        };
        IntentServiceAdapter ints = new IntentServiceAdapter() {
            @Override
            public Iterable<Intent> getIntentsByAppId(ApplicationId id) {
                return intents.values().stream().filter(i -> i.appId().equals(id)).collect(Collectors.toList());
            }

            @Override
            public IntentState getIntentState(Key k) {
                return states.get(k);
            }

            @Override
            public List<Intent> getInstallableIntents(Key k) {
                return installables.getOrDefault(k, Collections.emptyList());
            }
        };
        qm = new QuotaManager(flows, ints, now::get);
    }

    @After
    public void tearDown() {
        MockIdGenerator.unbind();
    }

    private FlowRule rule(ApplicationId app, DeviceId d) {
        return DefaultFlowRule.builder().forDevice(d).fromApp(app).withPriority(prio++).makeTemporary(60)
                .withSelector(DefaultTrafficSelector.builder().matchInPort(PortNumber.portNumber(1)).build())
                .withTreatment(DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber(3)).build())
                .build();
    }

    private FlowRule stored(ApplicationId app, DeviceId d, FlowEntry.FlowEntryState st) {
        FlowRule r = rule(app, d);
        store.add(new DefaultFlowEntry(r, st));
        return r;
    }

    /** An H2H intent of {@code app} whose compilation produced flows on the given devices. */
    private Key intent(ApplicationId app, String key, IntentState st, DeviceId... compiledOn) {
        Key k = Key.of(key, app);
        Intent i = HostToHostIntent.builder().appId(app).key(k)
                .one(HostId.hostId(MacAddress.valueOf("00:00:00:00:01:01")))
                .two(HostId.hostId(MacAddress.valueOf("00:00:00:00:01:02"))).build();
        intents.put(k, i);
        states.put(k, st);
        List<FlowRule> rules = new ArrayList<>();
        for (DeviceId d : compiledOn) {
            rules.add(rule(INTENT_APP, d));      // compiled flows carry the intent framework's appId
        }
        installables.put(k, ImmutableList.of(new FlowRuleIntent(app, k, rules, Collections.emptyList(),
                PathIntent.ProtectionType.PRIMARY, null)));
        return k;
    }

    private static QuotaLimits lim(int flows, int perDev, int ints, int churn) {
        return new QuotaLimits(flows, perDev, ints, churn, 60);
    }

    @Test
    public void countsDirectPlusIntentCompiledFlows() {
        stored(T1, S2, FlowEntry.FlowEntryState.ADDED);
        stored(T1, S2, FlowEntry.FlowEntryState.PENDING_ADD);
        stored(T1, S3, FlowEntry.FlowEntryState.PENDING_REMOVE);   // being removed: not counted
        stored(T2, S2, FlowEntry.FlowEntryState.ADDED);            // other tenant
        intent(T1, "h2h", IntentState.INSTALLED, S2, S1, S3, S3, S1, S2);
        Usage u = qm.usage("t1", T1);
        assertEquals(2, u.getDirectFlowIds().size());
        assertEquals(6, u.getIntentFlows());
        assertEquals(8, u.totalFlows());
        assertEquals(4, u.onDevice(S2));
        assertEquals(2, u.onDevice(S1));
        assertEquals(1, u.getIntentKeys().size());
        // t2 is charged only its own direct flow, none of t1's intent flows
        Usage u2 = qm.usage("t2", T2);
        assertEquals(1, u2.totalFlows());
        assertEquals(0, u2.getIntentFlows());
    }

    @Test
    public void onlyWithdrawnFailedAndPurgingIntentsStopCounting() {
        for (IntentState st : new IntentState[]{IntentState.WITHDRAWN, IntentState.FAILED, IntentState.PURGE_REQ}) {
            intent(T1, "k-" + st, st, S2, S3);
        }
        // still being withdrawn: flows may still be on the switches -> keep counting
        intent(T1, "wreq", IntentState.WITHDRAW_REQ, S2, S3);
        intent(T1, "wing", IntentState.WITHDRAWING, S2, S3);
        intent(T1, "live", IntentState.INSTALLED, S2, S3);
        intent(T1, "compiling", IntentState.COMPILING);
        Usage u = qm.usage("t1", T1);
        assertEquals(4, u.getIntentKeys().size());
        assertEquals(6, u.getIntentFlows());
    }

    @Test
    public void pendingAddsCountUntilVisibleThenNotTwice() {
        List<FlowRule> rules = ImmutableList.of(rule(T1, S2), rule(T1, S2));
        qm.recordFlowAdds("t1", rules);
        assertEquals("not in the store yet, but counted", 2, qm.usage("t1", T1).totalFlows());
        store.add(new DefaultFlowEntry(rules.get(0), FlowEntry.FlowEntryState.PENDING_ADD));
        assertEquals("visible one not double counted", 2, qm.usage("t1", T1).totalFlows());
        now.addAndGet(QuotaManager.PENDING_TTL_NANOS + 1);
        assertEquals("never-appearing one expires", 1, qm.usage("t1", T1).totalFlows());
    }

    @Test
    public void pendingRemovesDropImmediately() {
        FlowRule a = stored(T1, S2, FlowEntry.FlowEntryState.ADDED);
        stored(T1, S2, FlowEntry.FlowEntryState.ADDED);
        qm.recordFlowRemoves("t1", ImmutableList.of(a));
        assertEquals(1, qm.usage("t1", T1).totalFlows());
        now.addAndGet(QuotaManager.PENDING_TTL_NANOS + 1);   // removal never happened: counts again
        assertEquals(2, qm.usage("t1", T1).totalFlows());
    }

    @Test
    public void flowQuotaTotalAndPerDevice() {
        intent(T1, "h2h", IntentState.INSTALLED, S2, S1, S3);          // 3 compiled
        stored(T1, S2, FlowEntry.FlowEntryState.ADDED);                // 1 direct, S2 now has 2
        QuotaLimits l = lim(6, 3, 10, 100);
        assertFalse(qm.checkFlowAdds("t1", T1, ImmutableList.of(rule(T1, S2)), l).isPresent());   // 5, S2=3
        Optional<QuotaViolation> dev = qm.checkFlowAdds("t1", T1,
                ImmutableList.of(rule(T1, S2), rule(T1, S2)), l);                                   // S2=4
        assertEquals(QuotaViolation.QUOTA_FLOWS_DEVICE, dev.get().getReason());
        assertEquals(409, dev.get().getStatus());
        Optional<QuotaViolation> tot = qm.checkFlowAdds("t1", T1,
                ImmutableList.of(rule(T1, S1), rule(T1, S3), rule(T1, S3)), l);                     // 7 > 6
        assertEquals(QuotaViolation.QUOTA_FLOWS, tot.get().getReason());
    }

    @Test
    public void replacingAnOwnedFlowAddsNothing() {
        FlowRule a = stored(T1, S2, FlowEntry.FlowEntryState.ADDED);
        assertFalse(qm.checkFlowAdds("t1", T1, ImmutableList.of(a), lim(1, 1, 1, 100)).isPresent());
    }

    /** The race the lock + ledger close: second request sees the first one's reservation. */
    @Test
    public void secondRequestSeesFirstOnesInFlightFlows() {
        QuotaLimits l = lim(3, 10, 10, 100);
        List<FlowRule> first = ImmutableList.of(rule(T1, S2), rule(T1, S3));
        assertFalse(qm.checkFlowAdds("t1", T1, first, l).isPresent());
        qm.recordFlowAdds("t1", first);                                  // store not updated yet
        Optional<QuotaViolation> second = qm.checkFlowAdds("t1", T1,
                ImmutableList.of(rule(T1, S2), rule(T1, S3)), l);
        assertEquals(QuotaViolation.QUOTA_FLOWS, second.get().getReason());
    }

    private static final Map<DeviceId, Integer> H2H_EST = ImmutableMap.of(S2, 2, S1, 2, S3, 2);

    @Test
    public void intentQuota() {
        intent(T1, "a", IntentState.INSTALLED, S2);
        QuotaLimits l = lim(100, 100, 2, 100);
        assertFalse(qm.checkIntentSubmit("t1", T1, Key.of("b", T1), H2H_EST, l).isPresent());
        qm.recordIntentSubmit("t1", Key.of("b", T1), H2H_EST);           // in flight
        assertEquals(QuotaViolation.QUOTA_INTENTS,
                qm.checkIntentSubmit("t1", T1, Key.of("c", T1), H2H_EST, l).get().getReason());
        assertFalse("resubmitting a held key replaces it",
                qm.checkIntentSubmit("t1", T1, Key.of("a", T1), H2H_EST, l).isPresent());
        states.put(Key.of("a", T1), IntentState.WITHDRAWN);              // withdrawn: frees a slot
        assertFalse(qm.checkIntentSubmit("t1", T1, Key.of("c", T1), H2H_EST, l).isPresent());
    }

    /** Auditor's finding 2: back-to-back submits must count the not-yet-compiled ones. */
    @Test
    public void backToBackSubmitsCountTheirEstimates() {
        QuotaLimits l = lim(10, 100, 100, 100);
        assertFalse(qm.checkIntentSubmit("t1", T1, Key.of("i1", T1), H2H_EST, l).isPresent());
        qm.recordIntentSubmit("t1", Key.of("i1", T1), H2H_EST);          // 6 estimated
        Optional<QuotaViolation> v = qm.checkIntentSubmit("t1", T1, Key.of("i2", T1), H2H_EST, l);
        assertEquals("6 + 6 > 10", QuotaViolation.QUOTA_FLOWS, v.get().getReason());
        Usage u = qm.usage("t1", T1);
        assertEquals(6, u.getIntentFlows());
        assertEquals(6, u.getEstimatedIntentFlows());
    }

    /** Auditor's finding 1: the per-device quota applies to intents. */
    @Test
    public void perDeviceQuotaAppliesToIntents() {
        QuotaLimits l = lim(100, 4, 100, 100);
        qm.recordIntentSubmit("t1", Key.of("i1", T1), H2H_EST);          // 2 on each switch
        assertFalse(qm.checkIntentSubmit("t1", T1, Key.of("i2", T1), H2H_EST, l).isPresent());
        qm.recordIntentSubmit("t1", Key.of("i2", T1), H2H_EST);          // 4 on each switch
        Optional<QuotaViolation> v = qm.checkIntentSubmit("t1", T1, Key.of("i3", T1), H2H_EST, l);
        assertEquals(QuotaViolation.QUOTA_FLOWS_DEVICE, v.get().getReason());
    }

    @Test
    public void estimateIsReplacedByRealRulesAndKeptWhileCompiling() {
        Key k = Key.of("i1", T1);
        qm.recordIntentSubmit("t1", k, H2H_EST);
        // in the store, still compiling, long after the pending TTL: estimate still counts
        Intent i = HostToHostIntent.builder().appId(T1).key(k)
                .one(HostId.hostId(MacAddress.valueOf("00:00:00:00:01:01")))
                .two(HostId.hostId(MacAddress.valueOf("00:00:00:00:01:02"))).build();
        intents.put(k, i);
        states.put(k, IntentState.COMPILING);
        now.addAndGet(QuotaManager.PENDING_TTL_NANOS * 3);
        assertEquals(6, qm.usage("t1", T1).getEstimatedIntentFlows());
        // compiled to 4 real rules: they replace the estimate
        intent(T1, "i1", IntentState.INSTALLED, S2, S3, S3, S2);
        Usage u = qm.usage("t1", T1);
        assertEquals(4, u.getIntentFlows());
        assertEquals(0, u.getEstimatedIntentFlows());
        assertEquals(0, u.onDevice(S1));
    }

    @Test
    public void notStoredSubmitExpires() {
        qm.recordIntentSubmit("t1", Key.of("lost", T1), H2H_EST);
        assertEquals(6, qm.usage("t1", T1).getIntentFlows());
        now.addAndGet(QuotaManager.PENDING_TTL_NANOS + 1);
        assertEquals(0, qm.usage("t1", T1).getIntentFlows());
    }

    @Test
    public void churnCountsAddsAndDeletesInWindow() {
        QuotaLimits l = lim(1000, 1000, 1000, 5);
        qm.recordChurn("t1", 3);
        assertFalse(qm.checkChurnOnly("t1", 2, l).isPresent());
        qm.recordChurn("t1", 2);
        Optional<QuotaViolation> v = qm.checkFlowAdds("t1", T1, ImmutableList.of(rule(T1, S2)), l);
        assertEquals(QuotaViolation.QUOTA_CHURN, v.get().getReason());
        assertEquals(429, v.get().getStatus());
        assertEquals(60, v.get().getRetryAfterSeconds());
        assertFalse("other tenants have their own window", qm.checkChurnOnly("t2", 5, l).isPresent());
        now.addAndGet(60 * SEC);
        assertFalse(qm.checkChurnOnly("t1", 5, l).isPresent());
        assertEquals(0, qm.churn("t1", 60));
    }

    @Test
    public void perTenantOverridesAndKeyValidation() throws Exception {
        ObjectNode root;
        try (InputStream in = getClass().getResourceAsStream("/tenants.json")) {
            root = (ObjectNode) new ObjectMapper().readTree(in);
        }
        ((ObjectNode) root.get("tenants").get("t1")).putObject("quotas")
                .put("maxFlowsPerTenant", 7).put("churnLimit", 3);
        TenantRegistry reg = TenantRegistry.fromJson(root);
        QuotaLimits base = lim(100, 40, 20, 200);
        QuotaLimits t1 = base.forTenant(reg.tenant("t1").get());
        assertEquals(7, t1.getMaxFlowsPerTenant());
        assertEquals(3, t1.getChurnLimit());
        assertEquals(40, t1.getMaxFlowsPerTenantPerDevice());
        assertEquals(base, base.forTenant(reg.tenant("t2").get()));

        ((ObjectNode) root.get("tenants").get("t2")).putObject("quotas").put("maxFlows", 1);
        try {
            TenantRegistry.fromJson(root);
            assertTrue("unknown quota key accepted", false);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unknown quota \"maxFlows\""));
        }
    }

    /** Audit minor 1: a key the gateway withdraws before it compiled leaves no phantom estimate. */
    @Test
    public void forgetIntentDropsPendingEstimate() {
        qm.recordIntentSubmit("t1", Key.of("fast", T1), H2H_EST);
        assertEquals(6, qm.usage("t1", T1).getIntentFlows());
        qm.forgetIntent("t1", Key.of("fast", T1));
        Usage u = qm.usage("t1", T1);
        assertEquals(0, u.getIntentFlows());
        assertEquals(0, u.getIntentKeys().size());
    }
}
