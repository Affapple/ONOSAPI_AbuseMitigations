package lu.uni.sdn.apigateway.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lu.uni.sdn.apigateway.acl.AppIdImposer;
import lu.uni.sdn.apigateway.acl.Ownership;
import lu.uni.sdn.apigateway.acl.TestCoreService;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onosproject.codec.CodecContext;
import org.onosproject.codec.JsonCodec;
import org.onosproject.codec.impl.CodecManager;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.MockIdGenerator;
import org.onosproject.net.intent.PointToPointIntent;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;

/**
 * B4 rules, one reason code each, on objects decoded by ONOS's real codecs from the
 * same JSON a tenant would POST (including the auditor's ACL-only payloads).
 */
public class PayloadValidatorTest {

    private static final String T1APP = "lu.uni.sdn.gw.t1";
    private static final String S1 = "of:0000000000000001";
    private static final String S2 = "of:0000000000000002";
    private static final String S3 = "of:0000000000000003";
    private static final ValidationLimits LIM = new ValidationLimits(100, 30000, 300);

    private final ObjectMapper mapper = new ObjectMapper();
    private final TestCoreService core = new TestCoreService();
    private CodecManager codecs;
    private CodecContext ctx;
    private Tenant t1;

    @Before
    public void setUp() throws Exception {
        MockIdGenerator.cleanBind();
        codecs = new CodecManager();
        codecs.activate();
        core.registerApplication(T1APP);
        ctx = new CodecContext() {
            @Override
            public ObjectMapper mapper() {
                return mapper;
            }

            @Override
            public <T> JsonCodec<T> codec(Class<T> c) {
                return codecs.getCodec(c);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T getService(Class<T> c) {
                if (c == CoreService.class) {
                    return (T) core;
                }
                throw new IllegalArgumentException("no service " + c);
            }
        };
        try (InputStream in = getClass().getResourceAsStream("/tenants.json")) {
            t1 = TenantRegistry.fromJson(mapper.readTree(in)).tenant("t1").get();
        }
    }

    @After
    public void tearDown() {
        MockIdGenerator.unbind();
    }

    // ---- helpers: decode exactly as FlowsResource / IntentsResource do -------------

    private FlowRule flowJson(String json) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree("{\"flows\":[" + json + "]}");
        return codecs.getCodec(FlowRule.class).decode(AppIdImposer.imposeOnFlows(root, T1APP), ctx).get(0);
    }

    private static String flow(String dev, int prio, String criteria, String instructions) {
        return "{\"priority\":" + prio + ",\"isPermanent\":false,\"timeout\":60,\"deviceId\":\"" + dev + "\","
                + "\"selector\":{\"criteria\":[" + criteria + "]},"
                + "\"treatment\":{\"instructions\":[" + instructions + "]}}";
    }

    private static String out(int p) {
        return "{\"type\":\"OUTPUT\",\"port\":\"" + p + "\"}";
    }

    private static final String IN1 = "{\"type\":\"IN_PORT\",\"port\":\"1\"}";

    private String flowReason(String json) throws Exception {
        return PayloadValidator.checkFlow(flowJson(json), t1, LIM).map(Ownership.Violation::getReason).orElse("ok");
    }

    private Intent intentJson(String json) throws Exception {
        return codecs.getCodec(Intent.class).decode(AppIdImposer.imposeOnIntent(mapper.readTree(json), T1APP), ctx);
    }

    private String intentReason(String json) throws Exception {
        return PayloadValidator.checkIntent(intentJson(json), t1, LIM).map(Ownership.Violation::getReason)
                .orElse("ok");
    }

    private static String p2p(String selectorCriteria) {
        return "{\"type\":\"PointToPointIntent\",\"priority\":100,"
                + "\"ingressPoint\":{\"device\":\"" + S2 + "\",\"port\":\"1\"},"
                + "\"egressPoint\":{\"device\":\"" + S3 + "\",\"port\":\"1\"}"
                + (selectorCriteria == null ? "" : ",\"selector\":{\"criteria\":[" + selectorCriteria + "]}") + "}";
    }

    // ---- the auditor's ACL-only payloads ---------------------------------------------

    @Test
    public void auditorCatchAllOnCoreAtHighPriority() throws Exception {
        assertEquals(PayloadValidator.BAD_PRIORITY, flowReason(flow(S1, 40001, "", out(2))));
        assertEquals(PayloadValidator.MATCH_NOT_OWNED, flowReason(flow(S1, 20000, "", out(2))));
    }

    @Test
    public void auditorEthDstOnlyToOwnEdge() throws Exception {
        assertEquals(PayloadValidator.MATCH_NOT_OWNED, flowReason(flow(S2, 20000,
                "{\"type\":\"ETH_DST\",\"mac\":\"00:00:00:00:02:01\"}", out(1))));
    }

    @Test
    public void auditorEmptySelectorOnS3() throws Exception {
        assertEquals(PayloadValidator.MATCH_NOT_OWNED, flowReason(flow(S3, 20000, "", out(1))));
    }

    @Test
    public void auditorP2pWithEmptySelector() throws Exception {
        assertEquals(PayloadValidator.SELECTOR_NOT_OWNED, intentReason(p2p(null)));
    }

    // ---- flow rules ------------------------------------------------------------------

    @Test
    public void flowMatchMustBeOwned() throws Exception {
        assertEquals(PayloadValidator.MATCH_NOT_OWNED, flowReason(flow(S2, 20000,
                "{\"type\":\"ETH_TYPE\",\"ethType\":\"0x0800\"}", out(3))));                       // ETH_TYPE only
        assertEquals(PayloadValidator.MATCH_NOT_OWNED, flowReason(flow(S1, 20000, IN1, out(2))));  // trunk in_port
        assertEquals(PayloadValidator.MATCH_NOT_OWNED, flowReason(flow(S1, 20000,
                "{\"type\":\"ETH_TYPE\",\"ethType\":\"0x0800\"},{\"type\":\"IPV4_SRC\",\"ip\":\"10.0.0.0/16\"}",
                out(2))));                                                                       // broader than subnet
        assertEquals(PayloadValidator.MATCH_NOT_OWNED, flowReason(flow(S1, 20000,
                "{\"type\":\"ETH_SRC\",\"mac\":\"00:00:00:00:02:01\"}", out(2))));               // foreign src only
    }

    @Test
    public void legitimateFlowsPass() throws Exception {
        assertEquals("ok", flowReason(flow(S2, 20000, IN1, out(3))));                             // own edge port
        assertEquals("ok", flowReason(flow(S1, 20000,
                "{\"type\":\"IN_PORT\",\"port\":\"1\"},{\"type\":\"ETH_SRC\",\"mac\":\"00:00:00:00:01:01\"}",
                out(2))));                                                                       // owned src, transit
        assertEquals("ok", flowReason(flow(S1, 20000,
                "{\"type\":\"ETH_TYPE\",\"ethType\":\"0x0800\"},{\"type\":\"IPV4_SRC\",\"ip\":\"10.0.1.1/32\"}",
                out(2))));                                                                       // owned IP src
        assertEquals("ok", flowReason(flow(S2, 100, IN1, out(3))));                               // min priority
        assertEquals("ok", flowReason(flow(S2, 30000, IN1, out(3))));                             // max priority
    }

    @Test
    public void dropRules() throws Exception {
        assertEquals(PayloadValidator.DROP_NOT_OWNED, flowReason(flow(S1, 20000, "", "")));       // drop-all
        assertEquals("ok", flowReason(flow(S2, 20000, IN1, "")));                                // drop own traffic
    }

    @Test
    public void priorityAndTimeouts() throws Exception {
        assertEquals(PayloadValidator.BAD_PRIORITY, flowReason(flow(S2, 99, IN1, out(3))));
        assertEquals(PayloadValidator.BAD_PRIORITY, flowReason(flow(S2, 30001, IN1, out(3))));
        assertEquals(PayloadValidator.PERMANENT_NOT_ALLOWED,
                flowReason(flow(S2, 20000, IN1, out(3)).replace("\"isPermanent\":false,\"timeout\":60",
                        "\"isPermanent\":true")));
        assertEquals(PayloadValidator.BAD_TIMEOUT,
                flowReason(flow(S2, 20000, IN1, out(3)).replace("\"timeout\":60", "\"timeout\":301")));
        assertEquals(PayloadValidator.BAD_TIMEOUT,
                flowReason(flow(S2, 20000, IN1, out(3)).replace("\"timeout\":60", "\"timeout\":0")));
    }

    @Test
    public void controllerOutputAndCriteriaAllowlist() throws Exception {
        assertEquals(PayloadValidator.CONTROLLER_OUTPUT, flowReason(flow(S2, 20000, IN1,
                "{\"type\":\"OUTPUT\",\"port\":\"CONTROLLER\"}")));
        assertEquals(PayloadValidator.CRITERION_NOT_ALLOWED, flowReason(flow(S2, 20000,
                IN1 + ",{\"type\":\"TUNNEL_ID\",\"tunnelId\":5}", out(3))));
        assertEquals(PayloadValidator.CRITERION_NOT_ALLOWED, flowReason(flow(S2, 20000,
                IN1 + ",{\"type\":\"ETH_TYPE\",\"ethType\":\"0x86dd\"},{\"type\":\"IPV6_SRC\",\"ip\":\"::1/128\"}",
                out(3))));
    }

    // ---- intents ---------------------------------------------------------------------

    @Test
    public void p2pSelectorMustCarryOwnedSource() throws Exception {
        assertEquals(PayloadValidator.SELECTOR_NOT_OWNED,
                intentReason(p2p("{\"type\":\"ETH_TYPE\",\"ethType\":\"0x0800\"}")));
        assertEquals(PayloadValidator.SELECTOR_NOT_OWNED,
                intentReason(p2p("{\"type\":\"ETH_SRC\",\"mac\":\"00:00:00:00:02:01\"}")));
        assertEquals("ok", intentReason(p2p("{\"type\":\"ETH_SRC\",\"mac\":\"00:00:00:00:01:01\"}")));
        assertEquals("ok", intentReason(p2p("{\"type\":\"ETH_TYPE\",\"ethType\":\"0x0800\"},"
                + "{\"type\":\"IPV4_SRC\",\"ip\":\"10.0.1.0/24\"}")));
    }

    @Test
    public void hostToHostAndPriority() throws Exception {
        String h2h = "{\"type\":\"HostToHostIntent\",\"priority\":%d,"
                + "\"one\":\"00:00:00:00:01:01/None\",\"two\":\"00:00:00:00:01:02/None\"}";
        assertEquals("ok", intentReason(String.format(h2h, 100)));
        assertEquals(PayloadValidator.BAD_PRIORITY, intentReason(String.format(h2h, 50)));
        assertEquals(PayloadValidator.BAD_PRIORITY, intentReason(String.format(h2h, 40000)));
    }

    @Test
    public void intentRewritesAndInstructions() {
        TrafficSelector own = DefaultTrafficSelector.builder()
                .matchEthSrc(MacAddress.valueOf("00:00:00:00:01:01")).build();
        assertEquals(PayloadValidator.REWRITE_NOT_OWNED, p2pWith(own, DefaultTrafficTreatment.builder()
                .setEthDst(MacAddress.valueOf("00:00:00:00:02:01")).build()));
        assertEquals(PayloadValidator.REWRITE_NOT_OWNED, p2pWith(own, DefaultTrafficTreatment.builder()
                .setIpDst(IpAddress.valueOf("10.0.2.1")).build()));
        assertEquals("ok", p2pWith(own, DefaultTrafficTreatment.builder()
                .setEthDst(MacAddress.valueOf("00:00:00:00:01:02")).setIpDst(IpAddress.valueOf("10.0.1.2")).build()));
        assertEquals(PayloadValidator.INSTRUCTION_NOT_ALLOWED, p2pWith(own, DefaultTrafficTreatment.builder()
                .setOutput(PortNumber.portNumber(2)).build()));
        TrafficSelector tunnel = DefaultTrafficSelector.builder()
                .matchEthSrc(MacAddress.valueOf("00:00:00:00:01:01")).matchTunnelId(5L).build();
        assertEquals(PayloadValidator.CRITERION_NOT_ALLOWED,
                p2pWith(tunnel, DefaultTrafficTreatment.emptyTreatment()));
    }

    private String p2pWith(TrafficSelector sel, TrafficTreatment tr) {
        Intent i = PointToPointIntent.builder().appId(core.getAppId(T1APP)).priority(100).selector(sel).treatment(tr)
                .filteredIngressPoint(new FilteredConnectPoint(ConnectPoint.deviceConnectPoint(S2 + "/1")))
                .filteredEgressPoint(new FilteredConnectPoint(ConnectPoint.deviceConnectPoint(S3 + "/1"))).build();
        return PayloadValidator.checkIntent(i, t1, LIM).map(Ownership.Violation::getReason).orElse("ok");
    }

    @Test
    public void matchPrerequisites() throws Exception {
        String ipv4 = "{\"type\":\"ETH_TYPE\",\"ethType\":\"0x0800\"}";
        assertEquals(PayloadValidator.MISSING_PREREQUISITE, flowReason(flow(S2, 20000,
                IN1 + ",{\"type\":\"IPV4_SRC\",\"ip\":\"10.0.1.1/32\"}", out(3))));      // no ETH_TYPE
        assertEquals(PayloadValidator.MISSING_PREREQUISITE, flowReason(flow(S2, 20000,
                IN1 + ",{\"type\":\"ETH_TYPE\",\"ethType\":\"0x0806\"},{\"type\":\"IP_PROTO\",\"protocol\":6}",
                out(3))));                                                                     // ARP + IP_PROTO
        assertEquals(PayloadValidator.MISSING_PREREQUISITE, flowReason(flow(S2, 20000,
                IN1 + "," + ipv4 + ",{\"type\":\"TCP_DST\",\"tcpPort\":80}", out(3))));     // no IP_PROTO
        assertEquals(PayloadValidator.MISSING_PREREQUISITE, flowReason(flow(S2, 20000,
                IN1 + "," + ipv4 + ",{\"type\":\"IP_PROTO\",\"protocol\":17},{\"type\":\"TCP_DST\",\"tcpPort\":80}",
                out(3))));                                                                     // UDP proto, TCP port
        assertEquals("ok", flowReason(flow(S2, 20000,
                IN1 + "," + ipv4 + ",{\"type\":\"IP_PROTO\",\"protocol\":6},{\"type\":\"TCP_DST\",\"tcpPort\":80}",
                out(3))));
        assertEquals("ok", flowReason(flow(S2, 20000,
                IN1 + "," + ipv4 + ",{\"type\":\"IP_PROTO\",\"protocol\":1},{\"type\":\"ICMPV4_TYPE\",\"icmpType\":8}",
                out(3))));
        assertEquals(PayloadValidator.MISSING_PREREQUISITE,
                intentReason(p2p("{\"type\":\"IPV4_SRC\",\"ip\":\"10.0.1.0/24\"}")));
    }

    @Test
    public void intentConstraintsAreCheckedOnTheJson() throws Exception {
        String h2h = "{\"type\":\"HostToHostIntent\",\"one\":\"00:00:00:00:01:01/None\","
                + "\"two\":\"00:00:00:00:01:02/None\"%s}";
        assertEquals("ok", PayloadValidator.checkIntentJson(mapper.readTree(String.format(h2h, "")))
                .map(Ownership.Violation::getReason).orElse("ok"));
        assertEquals("ok", PayloadValidator.checkIntentJson(mapper.readTree(String.format(h2h, ",\"constraints\":[]")))
                .map(Ownership.Violation::getReason).orElse("ok"));
        assertEquals(PayloadValidator.CONSTRAINTS_NOT_ALLOWED, PayloadValidator.checkIntentJson(mapper.readTree(
                String.format(h2h, ",\"constraints\":[{\"type\":\"BandwidthConstraint\",\"bandwidth\":1}]"))).get()
                .getReason());
        // why the JSON and not the decoded intent: ONOS adds its own default constraint to every H2H
        assertEquals(1, ((org.onosproject.net.intent.ConnectivityIntent) intentJson(String.format(h2h, "")))
                .constraints().size());
    }
}
