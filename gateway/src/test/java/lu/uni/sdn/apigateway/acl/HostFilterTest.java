package lu.uni.sdn.apigateway.acl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tenants only see their own hosts in /gw/v1/hosts (B1 design change from the threat model).
 */
public class HostFilterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Tenant t1;
    private Tenant t2;

    /** Shape of GET /onos/v1/hosts (HostCodec), trimmed. */
    private static final String HOSTS = "{\"hosts\":["
            + "{\"id\":\"00:00:00:00:01:01/None\",\"mac\":\"00:00:00:00:01:01\",\"ipAddresses\":[\"10.0.1.1\"],"
            + "\"locations\":[{\"elementId\":\"of:0000000000000002\",\"port\":\"1\"}]},"
            + "{\"id\":\"00:00:00:00:02:01/None\",\"mac\":\"00:00:00:00:02:01\",\"ipAddresses\":[\"10.0.2.1\"]},"
            + "{\"id\":\"00:00:00:00:01:02/None\",\"mac\":\"00:00:00:00:01:02\",\"ipAddresses\":[\"10.0.1.2\"]},"
            + "{\"id\":\"00:00:00:00:02:02/None\",\"mac\":\"00:00:00:00:02:02\",\"ipAddresses\":[\"10.0.2.2\"]},"
            + "{\"id\":\"00:00:00:00:09:09/None\",\"mac\":\"00:00:00:00:09:09\",\"ipAddresses\":[\"10.0.9.9\"]},"
            + "{\"id\":\"no-mac\"}"
            + "]}";

    @Before
    public void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/tenants.json")) {
            TenantRegistry reg = TenantRegistry.fromJson(mapper.readTree(in));
            t1 = reg.tenant("t1").get();
            t2 = reg.tenant("t2").get();
        }
    }

    private List<String> macs(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.get("hosts").forEach(h -> out.add(h.get("mac").asText()));
        return out;
    }

    @Test
    public void listKeepsOnlyOwnHosts() throws Exception {
        assertEquals(List.of("00:00:00:00:01:01", "00:00:00:00:01:02"),
                macs(HostFilter.filterList(mapper.readTree(HOSTS), t1)));
        assertEquals(List.of("00:00:00:00:02:01", "00:00:00:00:02:02"),
                macs(HostFilter.filterList(mapper.readTree(HOSTS), t2)));
    }

    @Test
    public void singleHostOnlyIfOwned() throws Exception {
        JsonNode mine = mapper.readTree("{\"id\":\"00:00:00:00:01:01/None\",\"mac\":\"00:00:00:00:01:01\"}");
        JsonNode theirs = mapper.readTree("{\"id\":\"00:00:00:00:02:01/None\",\"mac\":\"00:00:00:00:02:01\"}");
        JsonNode noMac = mapper.readTree("{\"id\":\"x\"}");
        assertTrue(HostFilter.filterOne(mine, t1).isPresent());
        assertFalse(HostFilter.filterOne(theirs, t1).isPresent());
        assertTrue(HostFilter.filterOne(theirs, t2).isPresent());
        assertFalse(HostFilter.filterOne(noMac, t1).isPresent());
    }

    @Test
    public void unexpectedShapesFailClosed() throws Exception {
        for (String bad : new String[]{"[]", "{}", "{\"hosts\":{}}", "\"x\""}) {
            try {
                HostFilter.filterList(mapper.readTree(bad), t1);
                fail("accepted list " + bad);
            } catch (IllegalStateException expected) {
                // ok
            }
        }
        try {
            HostFilter.filterOne(mapper.readTree("[]"), t1);
            fail("accepted single []");
        } catch (IllegalStateException expected) {
            // ok
        }
    }
}
