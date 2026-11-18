package lu.uni.sdn.apigateway.quota;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onlab.graph.ScalarWeight;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.DefaultApplicationId;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DefaultLink;
import org.onosproject.net.DefaultPath;
import org.onosproject.net.DeviceId;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.HostId;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.provider.ProviderId;
import org.onosproject.net.intent.HostToHostIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.MockIdGenerator;
import org.onosproject.net.intent.PointToPointIntent;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;

/**
 * Flow estimates per device, using the counts measured live on ONOS 2.7:
 * H2H = 2 per path switch, P2P = 1 per path switch.
 */
public class IntentFlowEstimatorTest {

    private static final DeviceId S1 = DeviceId.deviceId("of:0000000000000001");
    private static final DeviceId S2 = DeviceId.deviceId("of:0000000000000002");
    private static final DeviceId S3 = DeviceId.deviceId("of:0000000000000003");
    private static final ApplicationId APP = new DefaultApplicationId(179, "lu.uni.sdn.gw.t1");
    private static final HostId H1A = HostId.hostId(MacAddress.valueOf("00:00:00:00:01:01"));
    private static final HostId H1B = HostId.hostId(MacAddress.valueOf("00:00:00:00:01:02"));
    private static final HostId H2A = HostId.hostId(MacAddress.valueOf("00:00:00:00:02:01"));
    private static final Set<DeviceId> ALL = ImmutableSet.of(S1, S2, S3);

    /** Host locations as in the topology: h1a s2/1, h1b s3/1, h2a s2/2. */
    private static final Function<HostId, Optional<ConnectPoint>> LOCATE = h -> {
        Map<HostId, ConnectPoint> m = ImmutableMap.of(H1A, cp("of:0000000000000002/1"),
                H1B, cp("of:0000000000000003/1"), H2A, cp("of:0000000000000002/2"));
        return Optional.ofNullable(m.get(h));
    };

    /** s2 -3- s1 -3- ... : s2/3-s1/1, s1/2-s3/3. */
    private static final BiFunction<DeviceId, DeviceId, Set<Path>> PATHS = (a, b) -> {
        if (a.equals(S2) && b.equals(S3)) {
            return ImmutableSet.of(path(link("of:0000000000000002/3", "of:0000000000000001/1"),
                                        link("of:0000000000000001/2", "of:0000000000000003/3")));
        }
        if (a.equals(S3) && b.equals(S2)) {
            return ImmutableSet.of(path(link("of:0000000000000003/3", "of:0000000000000001/2"),
                                        link("of:0000000000000001/1", "of:0000000000000002/3")));
        }
        return Collections.emptySet();
    };

    @Before
    public void setUp() {
        MockIdGenerator.cleanBind();
    }

    @After
    public void tearDown() {
        MockIdGenerator.unbind();
    }

    private static ConnectPoint cp(String s) {
        return ConnectPoint.deviceConnectPoint(s);
    }

    private static Link link(String a, String b) {
        return DefaultLink.builder().providerId(ProviderId.NONE).src(cp(a)).dst(cp(b)).type(Link.Type.DIRECT).build();
    }

    private static Path path(Link... links) {
        return new DefaultPath(ProviderId.NONE, ImmutableList.copyOf(links), ScalarWeight.toWeight(links.length));
    }

    private static Intent h2h(HostId a, HostId b) {
        return HostToHostIntent.builder().appId(APP).one(a).two(b).build();
    }

    private static Intent p2p(String in, String out) {
        return PointToPointIntent.builder().appId(APP).filteredIngressPoint(new FilteredConnectPoint(cp(in)))
                .filteredEgressPoint(new FilteredConnectPoint(cp(out))).build();
    }

    private static Map<DeviceId, Integer> est(Intent i) {
        return IntentFlowEstimator.estimate(i, LOCATE, PATHS, ALL);
    }

    @Test
    public void hostToHostAcrossCoreIsTwoPerSwitch() {
        assertEquals(ImmutableMap.of(S2, 2, S1, 2, S3, 2), est(h2h(H1A, H1B)));   // measured: 6 flows
    }

    @Test
    public void hostToHostOnOneSwitchIsTwo() {
        assertEquals(ImmutableMap.of(S2, 2), est(h2h(H1A, H2A)));                // measured: 2 flows on s2
    }

    @Test
    public void pointToPointIsOnePerSwitch() {
        assertEquals(ImmutableMap.of(S2, 1, S1, 1, S3, 1),
                est(p2p("of:0000000000000002/1", "of:0000000000000003/1")));    // measured: 3 flows
    }

    @Test
    public void unknownHostOrNoPathIsConservative() {
        HostId unknown = HostId.hostId(MacAddress.valueOf("00:00:00:00:09:09"));
        assertEquals(ImmutableMap.of(S1, 2, S2, 2, S3, 2), est(h2h(H1A, unknown)));
        Map<DeviceId, Integer> noPath = IntentFlowEstimator.estimate(
                p2p("of:0000000000000002/1", "of:0000000000000003/1"), LOCATE, (a, b) -> Collections.emptySet(),
                ALL);
        assertEquals(ImmutableMap.of(S1, 1, S2, 1, S3, 1), noPath);
    }
}
