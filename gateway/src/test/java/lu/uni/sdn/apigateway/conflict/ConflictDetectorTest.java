package lu.uni.sdn.apigateway.conflict;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import lu.uni.sdn.apigateway.acl.Ownership.Violation;
import lu.uni.sdn.apigateway.conflict.ConflictDetector.OwnedRule;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import org.junit.Before;
import org.junit.Test;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.DefaultApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B5 with real ONOS selectors/rules on the fixture topology: t1 owns s2/1, s3/1,
 * 00:00:00:00:01:0x, 10.0.1.0/24; t2 owns s2/2, s3/2, 00:00:00:00:02:0x, 10.0.2.0/24.
 */
public class ConflictDetectorTest {

    private static final DeviceId S1 = DeviceId.deviceId("of:0000000000000001");
    private static final DeviceId S2 = DeviceId.deviceId("of:0000000000000002");
    private static final ApplicationId APP = new DefaultApplicationId(179, "lu.uni.sdn.gw.t1");
    private static final ApplicationId INTENT = new DefaultApplicationId(5, "org.onosproject.net.intent");
    private static final MacAddress H1A = MacAddress.valueOf("00:00:00:00:01:01");
    private static final MacAddress H2A = MacAddress.valueOf("00:00:00:00:02:01");
    private static final MacAddress H2B = MacAddress.valueOf("00:00:00:00:02:02");

    private TenantRegistry reg;
    private Tenant t1;

    @Before
    public void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/tenants.json")) {
            reg = TenantRegistry.fromJson(new ObjectMapper().readTree(in));
        }
        t1 = reg.tenant("t1").get();
    }

    private static TrafficSelector.Builder sel() {
        return DefaultTrafficSelector.builder();
    }

    private static FlowRule rule(DeviceId d, int prio, TrafficSelector s, ApplicationId app) {
        return DefaultFlowRule.builder().forDevice(d).fromApp(app).withPriority(prio).makeTemporary(60)
                .withSelector(s).withTreatment(DefaultTrafficTreatment.builder()
                        .setOutput(PortNumber.portNumber(2)).build()).build();
    }

    /** t2's H2H h2a<->h2b as ONOS compiles it on s1 (measured shape: IN_PORT+ETH_SRC+ETH_DST, prio 100). */
    private static List<OwnedRule> t2CompiledOnS1() {
        return ImmutableList.of(
                new OwnedRule("t2", rule(S1, 100, sel().matchInPort(PortNumber.portNumber(1)).matchEthSrc(H2A)
                        .matchEthDst(H2B).build(), INTENT)),
                new OwnedRule("t2", rule(S1, 100, sel().matchInPort(PortNumber.portNumber(2)).matchEthSrc(H2B)
                        .matchEthDst(H2A).build(), INTENT)));
    }

    private String overlap(FlowRule r, List<OwnedRule> existing) {
        return ConflictDetector.checkOverlap(r, "t1", reg, existing, ConflictDetector.OVERLAP_CONFLICT)
                .map(Violation::getReason).orElse("ok");
    }

    // ---- field-by-field overlap --------------------------------------------------

    @Test
    public void wildcardsOverlap() {
        assertTrue(ConflictDetector.fieldsOverlap(sel().build(), sel().matchEthSrc(H2A).build()));
        assertTrue(ConflictDetector.fieldsOverlap(sel().matchInPort(PortNumber.portNumber(1)).build(),
                sel().matchEthDst(H2B).build()));
    }

    @Test
    public void differentExactValuesAreDisjoint() {
        assertFalse(ConflictDetector.fieldsOverlap(sel().matchInPort(PortNumber.portNumber(1)).build(),
                sel().matchInPort(PortNumber.portNumber(2)).build()));
        assertFalse(ConflictDetector.fieldsOverlap(sel().matchEthType(Ethernet.TYPE_IPV4).build(),
                sel().matchEthType(Ethernet.TYPE_ARP).build()));
        assertFalse(ConflictDetector.fieldsOverlap(sel().matchEthSrc(H1A).build(), sel().matchEthSrc(H2A).build()));
    }

    @Test
    public void prefixesOverlapByContainmentInEitherDirection() {
        TrafficSelector wide = sel().matchEthType(Ethernet.TYPE_IPV4).matchIPDst(IpPrefix.valueOf("10.0.0.0/16"))
                .build();
        TrafficSelector narrow = sel().matchEthType(Ethernet.TYPE_IPV4).matchIPDst(IpPrefix.valueOf("10.0.2.2/32"))
                .build();
        TrafficSelector other = sel().matchEthType(Ethernet.TYPE_IPV4).matchIPDst(IpPrefix.valueOf("10.0.1.0/24"))
                .build();
        assertTrue(ConflictDetector.fieldsOverlap(wide, narrow));
        assertTrue(ConflictDetector.fieldsOverlap(narrow, wide));
        assertFalse(ConflictDetector.fieldsOverlap(narrow, other));
    }

    // ---- overlap with priorities and source ownership ---------------------------

    @Test
    public void broadRuleConflictsAtEqualOrHigherPriorityOnly() {
        TrafficSelector trunkOnly = sel().matchInPort(PortNumber.portNumber(1)).build();   // no owned source
        assertEquals(ConflictDetector.OVERLAP_CONFLICT, overlap(rule(S1, 20000, trunkOnly, APP), t2CompiledOnS1()));
        assertEquals(ConflictDetector.OVERLAP_CONFLICT, overlap(rule(S1, 100, trunkOnly, APP), t2CompiledOnS1()));
        assertEquals("ok", overlap(rule(S1, 99, trunkOnly, APP), t2CompiledOnS1()));        // lower: allowed
        assertEquals("ok", overlap(rule(S2, 20000, trunkOnly, APP), t2CompiledOnS1()));     // other device
        TrafficSelector otherTrunk = sel().matchInPort(PortNumber.portNumber(3)).build();   // disjoint IN_PORT
        assertEquals("ok", overlap(rule(S1, 20000, otherTrunk, APP), t2CompiledOnS1()));
    }

    /** The auditor's DoS concern: legitimate owned-source rules never conflict. */
    @Test
    public void ownedSourceRulesNeverConflictEvenWithBroadForeignRules() {
        List<OwnedRule> existing = ImmutableList.<OwnedRule>builder().addAll(t2CompiledOnS1())
                // t2 rules with no owned source at all (possible only with B4 off): still no false conflict
                .add(new OwnedRule("t2", rule(S1, 30000, sel().build(), APP)))
                .add(new OwnedRule("t2", rule(S1, 30000, sel().matchInPort(PortNumber.portNumber(1)).build(), APP)))
                .build();
        assertEquals("ok", overlap(rule(S1, 30000, sel().matchInPort(PortNumber.portNumber(1)).matchEthSrc(H1A)
                .build(), APP), existing));
        assertEquals("ok", overlap(rule(S1, 30000, sel().matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(IpPrefix.valueOf("10.0.1.1/32")).build(), APP), existing));
        assertEquals("ok", overlap(rule(S2, 30000, sel().matchInPort(PortNumber.portNumber(1)).build(), APP),
                ImmutableList.of(new OwnedRule("t2", rule(S2, 30000, sel().build(), APP)))));
    }

    /** t2's legitimate H2H installed AFTER t1's broad rule is not punished (victim stays). */
    @Test
    public void victimsOwnedRulesDoNotConflictWithAttackersBroadRule() {
        List<OwnedRule> t1Broad = ImmutableList.of(
                new OwnedRule("t1", rule(S1, 100, sel().matchInPort(PortNumber.portNumber(1)).build(), INTENT)));
        FlowRule t2Compiled = t2CompiledOnS1().get(0).getRule();
        assertFalse(ConflictDetector.checkOverlap(t2Compiled, "t2", reg, t1Broad, ConflictDetector.COMPILED_CONFLICT)
                .isPresent());
    }

    @Test
    public void ownRulesAreIgnored() {
        List<OwnedRule> mine = ImmutableList.of(new OwnedRule("t1", rule(S1, 100, sel().build(), APP)));
        assertEquals("ok", overlap(rule(S1, 20000, sel().build(), APP), mine));
    }

    // ---- spoofing, rewrites, destination capture --------------------------------

    private String sources(TrafficSelector s) {
        return ConflictDetector.checkSources(s, t1).map(Violation::getReason).orElse("ok");
    }

    @Test
    public void spoofedSources() {
        assertEquals(ConflictDetector.SPOOFED_SOURCE, sources(sel().matchInPort(PortNumber.portNumber(1))
                .matchEthSrc(H2A).build()));                                   // foreign src next to owned port
        assertEquals(ConflictDetector.SPOOFED_SOURCE, sources(sel().matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(IpPrefix.valueOf("10.0.2.1/32")).build()));
        assertEquals(ConflictDetector.SPOOFED_SOURCE, sources(sel().matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(IpPrefix.valueOf("10.0.0.0/16")).build()));        // broader than own subnet
        assertEquals(ConflictDetector.SPOOFED_SOURCE, sources(sel()
                .matchEthSrcMasked(H1A, MacAddress.valueOf("ff:ff:ff:00:00:00")).build()));
        assertEquals("ok", sources(sel().matchEthSrc(H1A).build()));
        assertEquals("ok", sources(sel().matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(IpPrefix.valueOf("10.0.1.0/24")).build()));
    }

    private String rewrites(TrafficTreatment t) {
        return ConflictDetector.checkRewrites(t, t1).map(Violation::getReason).orElse("ok");
    }

    @Test
    public void foreignRewrites() {
        assertEquals(ConflictDetector.FOREIGN_REWRITE, rewrites(DefaultTrafficTreatment.builder().setEthSrc(H2A)
                .build()));
        assertEquals(ConflictDetector.FOREIGN_REWRITE, rewrites(DefaultTrafficTreatment.builder().setEthDst(H2B)
                .build()));
        assertEquals(ConflictDetector.FOREIGN_REWRITE, rewrites(DefaultTrafficTreatment.builder()
                .setIpSrc(IpAddress.valueOf("10.0.2.1")).build()));
        assertEquals(ConflictDetector.FOREIGN_REWRITE, rewrites(DefaultTrafficTreatment.builder()
                .setIpDst(IpAddress.valueOf("10.0.2.2")).build()));
        assertEquals("ok", rewrites(DefaultTrafficTreatment.builder().setEthSrc(H1A)
                .setIpDst(IpAddress.valueOf("10.0.1.2")).setOutput(PortNumber.portNumber(3)).build()));
    }

    private String capture(TrafficSelector s) {
        return ConflictDetector.checkDstCapture(s, t1, reg).map(Violation::getReason).orElse("ok");
    }

    @Test
    public void destinationCapture() {
        assertEquals(ConflictDetector.DST_CAPTURE, capture(sel().matchInPort(PortNumber.portNumber(1))
                .matchEthSrc(H1A).matchEthDst(H2B).build()));                  // even with an owned source
        assertEquals(ConflictDetector.DST_CAPTURE, capture(sel().matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(IpPrefix.valueOf("10.0.2.0/24")).build()));
        assertEquals(ConflictDetector.DST_CAPTURE, capture(sel().matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(IpPrefix.valueOf("10.0.0.0/16")).build()));        // covers t2's subnet
        assertEquals("ok", capture(sel().matchEthDst(MacAddress.valueOf("00:00:00:00:01:02")).build()));
        assertEquals("ok", capture(sel().matchEthDst(MacAddress.BROADCAST).build()));   // no tenant's
        assertEquals("ok", capture(sel().matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(IpPrefix.valueOf("10.0.1.2/32")).build()));
    }

    @Test
    public void ownersFromSourceFields() {
        assertEquals("[t1]", String.valueOf(ConflictDetector.owners(sel().matchEthSrc(H1A).build(), S1, reg)));
        assertEquals(null, ConflictDetector.owners(sel().matchInPort(PortNumber.portNumber(1)).build(), S1, reg));
        assertEquals("[t1]", String.valueOf(ConflictDetector.owners(sel().matchInPort(PortNumber.portNumber(1))
                .build(), S2, reg)));                                           // s2/1 is t1's edge port
        assertEquals(Optional.empty(), Optional.ofNullable(ConflictDetector.owners(sel().build(), S1, reg)));
    }
}
