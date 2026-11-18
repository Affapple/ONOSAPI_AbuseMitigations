package lu.uni.sdn.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lu.uni.sdn.apigateway.acl.TestCoreService;
import lu.uni.sdn.apigateway.tenant.TenantsConfig;
import org.junit.Before;
import org.junit.Test;
import org.onosproject.cfg.ComponentConfigAdapter;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistryAdapter;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Reload rules of GatewayManager, driven through fake netcfg events:
 * absent -> empty; valid -> loaded + app ids registered; invalid -> previous kept;
 * removed -> empty. Uses onos-api's test adapters.
 */
public class GatewayManagerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TestCoreService core = new TestCoreService();
    private FakeNetcfg netcfg;
    private GatewayManager mgr;
    private ObjectNode fixture;

    /** Netcfg registry whose single TenantsConfig the test controls. */
    private static final class FakeNetcfg extends NetworkConfigRegistryAdapter {
        JsonNode json;
        NetworkConfigListener listener;

        @Override
        public void addListener(NetworkConfigListener l) {
            listener = l;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S, C extends Config<S>> C getConfig(S subject, Class<C> configClass) {
            if (json == null || configClass != TenantsConfig.class) {
                return null;
            }
            TenantsConfig c = new TenantsConfig();
            c.init((ApplicationId) subject, TenantsConfig.KEY, json, new ObjectMapper(), cfg -> { });
            return (C) c;
        }

        void fire(NetworkConfigEvent.Type type) {
            NetworkConfigEvent e = new NetworkConfigEvent(type, "subject", TenantsConfig.class);
            if (listener.isRelevant(e)) {
                listener.event(e);
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/tenants.json")) {
            fixture = (ObjectNode) mapper.readTree(in);
        }
        netcfg = new FakeNetcfg();
        mgr = new GatewayManager();
        mgr.coreService = core;
        mgr.cfgService = new ComponentConfigAdapter();
        mgr.netcfgRegistry = netcfg;
    }

    @Test
    public void absentAtActivationMeansEmpty() {
        mgr.activate(null);
        assertSame(GatewaySnapshot.empty(), mgr.getSnapshot());
        assertTrue(mgr.getSnapshot().registry().tenants().isEmpty());
    }

    @Test
    public void presentAtActivationLoadsAndRegistersAppIds() {
        netcfg.json = fixture;
        mgr.activate(null);
        GatewaySnapshot s = mgr.getSnapshot();
        assertEquals(2, s.registry().tenants().size());
        assertEquals("lu.uni.sdn.gw.t1", s.appId("t1").get().name());
        assertEquals("lu.uni.sdn.gw.t2", s.appId("t2").get().name());
        assertTrue(core.names().contains("lu.uni.sdn.gw.t1"));
        assertEquals("t2", s.tenantOfApp(s.appId("t2").get()).get().getId());
    }

    @Test
    public void addedUpdatedInvalidRemoved() {
        mgr.activate(null);
        netcfg.json = fixture;
        netcfg.fire(NetworkConfigEvent.Type.CONFIG_ADDED);
        GatewaySnapshot first = mgr.getSnapshot();
        assertEquals(2, first.registry().tenants().size());

        // invalid update: previous snapshot is kept (same object)
        ObjectNode bad = fixture.deepCopy();
        bad.put("bogus", 1);
        netcfg.json = bad;
        netcfg.fire(NetworkConfigEvent.Type.CONFIG_UPDATED);
        assertSame(first, mgr.getSnapshot());

        // valid update with a third tenant: new snapshot, new app id
        ObjectNode three = fixture.deepCopy();
        ObjectNode t3 = ((ObjectNode) three.get("tenants")).putObject("t3");
        t3.putArray("subnets").add("10.0.3.0/24");
        t3.putArray("hosts");
        t3.putArray("edgePorts");
        t3.putObject("accounts").put("t3-op", "operator");
        netcfg.json = three;
        netcfg.fire(NetworkConfigEvent.Type.CONFIG_UPDATED);
        GatewaySnapshot second = mgr.getSnapshot();
        assertNotSame(first, second);
        assertEquals("lu.uni.sdn.gw.t3", second.appId("t3").get().name());
        assertEquals("t3", second.registry().tenantOf("t3-op").get().getId());
        // app ids of existing tenants are stable across reloads
        assertEquals(first.appId("t1").get(), second.appId("t1").get());

        // removed: fail closed
        netcfg.json = null;
        netcfg.fire(NetworkConfigEvent.Type.CONFIG_REMOVED);
        assertSame(GatewaySnapshot.empty(), mgr.getSnapshot());
    }

    @Test
    public void defaultsWithoutComponentContext() {
        mgr.activate(null);
        assertTrue(mgr.getToggles().isAclEnabled());
        assertEquals("http://127.0.0.1:8181/onos/v1/", mgr.getProxySettings().getBaseUrl());
        assertEquals("gw-svc-pw", mgr.getProxySettings().getServiceAccountPassword());
    }
}
