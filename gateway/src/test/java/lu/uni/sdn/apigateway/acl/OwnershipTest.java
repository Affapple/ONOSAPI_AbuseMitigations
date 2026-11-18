package lu.uni.sdn.apigateway.acl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.DefaultApplicationId;
import org.onosproject.core.GroupId;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.HostId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.StatTriggerField;
import org.onosproject.net.flow.StatTriggerFlag;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.intent.HostToHostIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.MockIdGenerator;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.intent.SinglePointToMultiPointIntent;

import java.io.InputStream;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Ownership checks against the fixture topology: t1 owns s2/1 and s3/1,
 * t2 owns s2/2 and s3/2, trunks are s1/1, s1/2, s2/3, s3/3.
 */
public class OwnershipTest {

    private static final DeviceId S1 = DeviceId.deviceId("of:0000000000000001");
    private static final DeviceId S2 = DeviceId.deviceId("of:0000000000000002");
    private static final DeviceId S3 = DeviceId.deviceId("of:0000000000000003");
    private static final ApplicationId APP = new DefaultApplicationId(179, "lu.uni.sdn.gw.t1");

    private TenantRegistry reg;
    private Tenant t1;

    @Before
    public void setUp() throws Exception {
        MockIdGenerator.cleanBind();
        try (InputStream in = getClass().getResourceAsStream("/tenants.json")) {
            reg = TenantRegistry.fromJson(new ObjectMapper().readTree(in));
        }
        t1 = reg.tenant("t1").get();
    }

    @After
    public void tearDown() {
        MockIdGenerator.unbind();
    }

    private static FlowRule flow(DeviceId dev, TrafficSelector sel, TrafficTreatment tr) {
        return DefaultFlowRule.builder().forDevice(dev).fromApp(APP).withPriority(40000)
                .makeTemporary(60).withSelector(sel).withTreatment(tr).build();
    }

    private static TrafficSelector inPort(long p) {
        return DefaultTrafficSelector.builder().matchInPort(PortNumber.portNumber(p)).build();
    }

    private static TrafficTreatment out(PortNumber p) {
        return DefaultTrafficTreatment.builder().setOutput(p).build();
    }

    private static TrafficTreatment out(long p) {
        return out(PortNumber.portNumber(p));
    }

    private String check(FlowRule r) {
        return Ownership.checkFlow(r, t1, reg).map(Ownership.Violation::getReason).orElse("ok");
    }

    @Test
    public void ownEdgeToTrunkAndBackOk() {
        assertEquals("ok", check(flow(S2, inPort(1), out(3))));
        assertEquals("ok", check(flow(S2, inPort(3), out(1))));
        assertEquals("ok", check(flow(S1, inPort(1), out(2))));   // core switch: trunk to trunk
        assertEquals("ok", check(flow(S3, DefaultTrafficSelector.emptySelector(), out(1))));
        assertEquals("ok", check(flow(S2, inPort(1), out(PortNumber.IN_PORT))));
        assertEquals("ok", check(flow(S2, inPort(1), DefaultTrafficTreatment.builder().drop().build())));
        // CONTROLLER passes the ACL; it is a B4 (validation) concern
        assertEquals("ok", check(flow(S2, inPort(1), out(PortNumber.CONTROLLER))));
    }

    @Test
    public void unknownDeviceRejected() {
        assertEquals(Reasons.DEVICE_NOT_OWNED,
                check(flow(DeviceId.deviceId("of:0000000000000099"), inPort(1), out(3))));
    }

    @Test
    public void otherTenantsPortsRejected() {
        assertEquals(Reasons.PORT_NOT_OWNED, check(flow(S2, inPort(2), out(3))));   // t2's edge as in-port
        assertEquals(Reasons.PORT_NOT_OWNED, check(flow(S2, inPort(1), out(2))));   // output to t2's edge
        assertEquals(Reasons.PORT_NOT_OWNED, check(flow(S3, inPort(3), out(2))));
        assertEquals(Reasons.PORT_NOT_OWNED, check(flow(S2, inPort(9), out(3))));   // unknown port
        assertEquals(Reasons.PORT_NOT_OWNED, check(flow(S1, inPort(1), out(9))));
    }

    @Test
    public void floodingLogicalPortsRejected() {
        for (PortNumber p : new PortNumber[]{PortNumber.FLOOD, PortNumber.ALL, PortNumber.NORMAL,
                PortNumber.LOCAL}) {
            assertEquals(p.toString(), Reasons.PORT_NOT_OWNED, check(flow(S2, inPort(1), out(p))));
        }
    }

    @Test
    public void groupsAndMetersRejected() {
        TrafficTreatment g = DefaultTrafficTreatment.builder().group(GroupId.valueOf(1)).build();
        assertEquals(Reasons.RESOURCE_NOT_OWNED, check(flow(S2, inPort(1), g)));
    }

    /** Auditor's finding: meters/table/metadata/stat-trigger live outside allInstructions(). */
    @Test
    public void meterTableMetadataStatTriggerRejected() {
        TrafficTreatment meter = DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber(3))
                .meter(MeterId.meterId(1)).build();
        assertFalse("meter must not be in allInstructions (precondition)",
                meter.allInstructions().stream().anyMatch(i -> i.type() == Instruction.Type.METER));
        assertEquals(Reasons.RESOURCE_NOT_OWNED, check(flow(S2, inPort(1), meter)));

        TrafficTreatment table = DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber(3))
                .transition(1).build();
        assertEquals(Reasons.INSTRUCTION_NOT_ALLOWED, check(flow(S2, inPort(1), table)));

        TrafficTreatment meta = DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber(3))
                .writeMetadata(1L, 0xffL).build();
        assertEquals(Reasons.INSTRUCTION_NOT_ALLOWED, check(flow(S2, inPort(1), meta)));

        TrafficTreatment stat = DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber(3))
                .add(Instructions.statTrigger(
                        com.google.common.collect.ImmutableMap.of(StatTriggerField.PACKET_COUNT, 10L),
                        StatTriggerFlag.ONLY_FIRST)).build();
        assertEquals(Reasons.INSTRUCTION_NOT_ALLOWED, check(flow(S2, inPort(1), stat)));

        // the same instructions added through the generic add() path
        TrafficTreatment meterViaAdd = DefaultTrafficTreatment.builder()
                .add(Instructions.meterTraffic(MeterId.meterId(2))).setOutput(PortNumber.portNumber(3)).build();
        assertEquals(Reasons.RESOURCE_NOT_OWNED, check(flow(S2, inPort(1), meterViaAdd)));
        TrafficTreatment tableViaAdd = DefaultTrafficTreatment.builder()
                .add(Instructions.transition(2)).setOutput(PortNumber.portNumber(3)).build();
        assertEquals(Reasons.INSTRUCTION_NOT_ALLOWED, check(flow(S2, inPort(1), tableViaAdd)));
    }

    @Test
    public void instructionAllowlist() {
        TrafficTreatment okRewrites = DefaultTrafficTreatment.builder()
                .setEthSrc(MacAddress.valueOf("00:00:00:00:01:01"))
                .setEthDst(MacAddress.valueOf("00:00:00:00:01:02"))
                .setVlanId(org.onlab.packet.VlanId.vlanId((short) 10)).pushVlan().popVlan()
                .setIpSrc(org.onlab.packet.IpAddress.valueOf("10.0.1.1")).decNwTtl()
                .setTcpDst(org.onlab.packet.TpPort.tpPort(80))
                .setOutput(PortNumber.portNumber(3)).build();
        assertEquals("ok", check(flow(S2, inPort(1), okRewrites)));
        TrafficTreatment noAction = DefaultTrafficTreatment.builder().add(Instructions.createNoAction()).build();
        assertEquals("ok", check(flow(S2, inPort(1), noAction)));

        TrafficTreatment mpls = DefaultTrafficTreatment.builder().pushMpls()
                .setOutput(PortNumber.portNumber(3)).build();
        assertEquals(Reasons.INSTRUCTION_NOT_ALLOWED, check(flow(S2, inPort(1), mpls)));
        TrafficTreatment tunnel = DefaultTrafficTreatment.builder().setTunnelId(5L)
                .setOutput(PortNumber.portNumber(3)).build();
        assertEquals(Reasons.INSTRUCTION_NOT_ALLOWED, check(flow(S2, inPort(1), tunnel)));
        TrafficTreatment arp = DefaultTrafficTreatment.builder().setArpOp((short) 2)
                .setOutput(PortNumber.portNumber(3)).build();
        assertEquals(Reasons.INSTRUCTION_NOT_ALLOWED, check(flow(S2, inPort(1), arp)));
        TrafficTreatment queue = DefaultTrafficTreatment.builder().setQueue(1)
                .setOutput(PortNumber.portNumber(3)).build();
        assertEquals(Reasons.RESOURCE_NOT_OWNED, check(flow(S2, inPort(1), queue)));
        TrafficTreatment deferred = DefaultTrafficTreatment.builder().deferred()
                .setOutput(PortNumber.FLOOD).build();
        assertEquals(Reasons.PORT_NOT_OWNED, check(flow(S2, inPort(1), deferred)));
    }

    @Test
    public void inPhyPortRejected() {
        TrafficSelector s = DefaultTrafficSelector.builder()
                .matchInPhyPort(PortNumber.portNumber(1)).build();
        assertEquals(Reasons.PORT_NOT_OWNED, check(flow(S2, s, out(3))));
    }

    private String check(Intent i) {
        return Ownership.checkIntent(i, t1).map(Ownership.Violation::getReason).orElse("ok");
    }

    private static HostId host(String mac) {
        return HostId.hostId(MacAddress.valueOf(mac));
    }

    @Test
    public void hostToHostOwnedHostsOnly() {
        assertEquals("ok", check(HostToHostIntent.builder().appId(APP)
                .one(host("00:00:00:00:01:01")).two(host("00:00:00:00:01:02")).build()));
        assertEquals(Reasons.ENDPOINT_NOT_OWNED, check(HostToHostIntent.builder().appId(APP)
                .one(host("00:00:00:00:01:01")).two(host("00:00:00:00:02:02")).build()));
        assertEquals(Reasons.ENDPOINT_NOT_OWNED, check(HostToHostIntent.builder().appId(APP)
                .one(host("00:00:00:00:02:01")).two(host("00:00:00:00:02:02")).build()));
    }

    private static PointToPointIntent p2p(ConnectPoint in, ConnectPoint out) {
        return PointToPointIntent.builder().appId(APP)
                .filteredIngressPoint(new FilteredConnectPoint(in))
                .filteredEgressPoint(new FilteredConnectPoint(out)).build();
    }

    @Test
    public void pointToPointOwnedEdgePortsOnly() {
        ConnectPoint s2p1 = ConnectPoint.deviceConnectPoint("of:0000000000000002/1");
        ConnectPoint s3p1 = ConnectPoint.deviceConnectPoint("of:0000000000000003/1");
        ConnectPoint s3p2 = ConnectPoint.deviceConnectPoint("of:0000000000000003/2");
        ConnectPoint s2p3 = ConnectPoint.deviceConnectPoint("of:0000000000000002/3");
        assertEquals("ok", check(p2p(s2p1, s3p1)));
        assertEquals(Reasons.ENDPOINT_NOT_OWNED, check(p2p(s2p1, s3p2)));   // t2's host port
        assertEquals(Reasons.ENDPOINT_NOT_OWNED, check(p2p(s2p1, s2p3)));   // trunk is not an endpoint
    }

    @Test
    public void otherIntentTypesRejected() {
        ConnectPoint s2p1 = ConnectPoint.deviceConnectPoint("of:0000000000000002/1");
        ConnectPoint s3p1 = ConnectPoint.deviceConnectPoint("of:0000000000000003/1");
        Intent sp2mp = SinglePointToMultiPointIntent.builder().appId(APP)
                .filteredIngressPoint(new FilteredConnectPoint(s2p1))
                .filteredEgressPoints(com.google.common.collect.ImmutableSet.of(new FilteredConnectPoint(s3p1)))
                .build();
        assertEquals(Reasons.UNSUPPORTED_INTENT_TYPE, check(sp2mp));
        Optional<Ownership.Violation> v = Ownership.checkIntent(sp2mp, t1);
        assertFalse(v.get().getMessage().isEmpty());
    }
}
