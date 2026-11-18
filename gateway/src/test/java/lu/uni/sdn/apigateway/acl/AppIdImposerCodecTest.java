package lu.uni.sdn.apigateway.acl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onosproject.codec.CodecContext;
import org.onosproject.codec.JsonCodec;
import org.onosproject.codec.impl.CodecManager;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.intent.HostToHostIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.MockIdGenerator;
import org.onosproject.net.intent.PointToPointIntent;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * O3/O4: decode flows and intents with ONOS's real codecs (CodecManager from
 * onos-core-common 2.7.0) after the gateway has imposed the tenant's appId, and
 * check that a client-supplied appId (another tenant's) does not survive.
 */
public class AppIdImposerCodecTest {

    private static final String T1 = "lu.uni.sdn.gw.t1";
    private static final String T2 = "lu.uni.sdn.gw.t2";

    private final ObjectMapper mapper = new ObjectMapper();
    private final TestCoreService core = new TestCoreService();
    private CodecManager codecs;
    private CodecContext ctx;
    private ApplicationId t1;

    @Before
    public void setUp() {
        MockIdGenerator.cleanBind();
        codecs = new CodecManager();
        codecs.activate();
        t1 = core.registerApplication(T1);
        core.registerApplication(T2);
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
    }

    @After
    public void tearDown() {
        MockIdGenerator.unbind();
    }

    private ObjectNode flowsBody(String clientAppId) throws Exception {
        String flow = "{\"priority\":40000,\"isPermanent\":false,\"timeout\":60,"
                + "\"deviceId\":\"of:0000000000000002\","
                + (clientAppId == null ? "" : "\"appId\":\"" + clientAppId + "\",")
                + "\"treatment\":{\"instructions\":[{\"type\":\"OUTPUT\",\"port\":\"3\"}]},"
                + "\"selector\":{\"criteria\":[{\"type\":\"IN_PORT\",\"port\":\"1\"}]}}";
        return (ObjectNode) mapper.readTree("{\"flows\":[" + flow + "," + flow + "]}");
    }

    @Test
    public void o3FlowGetsImposedAppIdEvenIfClientNamesAnotherTenant() throws Exception {
        ArrayNode flows = AppIdImposer.imposeOnFlows(flowsBody(T2), T1);
        List<FlowRule> rules = codecs.getCodec(FlowRule.class).decode(flows, ctx);
        assertEquals(2, rules.size());
        for (FlowRule r : rules) {
            assertEquals(t1.id(), r.appId());
            assertEquals("of:0000000000000002", r.deviceId().toString());
        }
    }

    @Test
    public void o3FlowWithoutAppIdGetsImposedAppId() throws Exception {
        ArrayNode flows = AppIdImposer.imposeOnFlows(flowsBody(null), T1);
        for (FlowRule r : codecs.getCodec(FlowRule.class).decode(flows, ctx)) {
            assertEquals(t1.id(), r.appId());
        }
    }

    /** Documents why imposing is needed: without it the codec registers whatever the client sends. */
    @Test
    public void withoutImposingTheCodecRegistersAnyClientAppId() throws Exception {
        ObjectNode body = flowsBody("made.up.by.client");
        List<FlowRule> rules = codecs.getCodec(FlowRule.class)
                .decode((ArrayNode) body.get("flows"), ctx);
        assertTrue(core.names().contains("made.up.by.client"));
        assertEquals(core.getAppId("made.up.by.client").id(), rules.get(0).appId());
    }

    @Test
    public void o4HostToHostIntentGetsImposedAppIdAndKey() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree("{\"type\":\"HostToHostIntent\",\"appId\":\"" + T2 + "\","
                + "\"key\":\"k1\",\"priority\":100,"
                + "\"one\":\"00:00:00:00:01:01/None\",\"two\":\"00:00:00:00:01:02/None\"}");
        Intent i = codecs.getCodec(Intent.class).decode(AppIdImposer.imposeOnIntent(body, T1), ctx);
        assertTrue(i instanceof HostToHostIntent);
        assertEquals(t1, i.appId());
        assertEquals(Key.of("k1", t1), i.key());
        assertFalse(Key.of("k1", core.getAppId(T2)).equals(i.key()));
    }

    @Test
    public void o4PointToPointIntentGetsImposedAppId() throws Exception {
        ObjectNode body = (ObjectNode) mapper.readTree("{\"type\":\"PointToPointIntent\",\"appId\":\"" + T2 + "\","
                + "\"ingressPoint\":{\"device\":\"of:0000000000000002\",\"port\":\"1\"},"
                + "\"egressPoint\":{\"device\":\"of:0000000000000003\",\"port\":\"1\"}}");
        Intent i = codecs.getCodec(Intent.class).decode(AppIdImposer.imposeOnIntent(body, T1), ctx);
        assertTrue(i instanceof PointToPointIntent);
        assertEquals(t1, i.appId());
    }

    @Test
    public void malformedEnvelopesRejected() throws Exception {
        for (String bad : new String[]{"[]", "{}", "{\"flows\":{}}", "{\"flows\":[]}", "{\"flows\":[1]}"}) {
            try {
                AppIdImposer.imposeOnFlows(mapper.readTree(bad), T1);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        try {
            AppIdImposer.imposeOnIntent(mapper.readTree("[]"), T1);
            fail("accepted array intent");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
