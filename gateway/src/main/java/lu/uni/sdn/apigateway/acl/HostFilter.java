package lu.uni.sdn.apigateway.acl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lu.uni.sdn.apigateway.tenant.Tenant;
import org.onlab.packet.MacAddress;

import java.util.Optional;

/**
 * Filters ONOS host JSON (GET /onos/v1/hosts[/...]) down to the caller's tenant.
 * Another tenant's MAC, IP and location are exactly what a cross-tenant attack
 * (S4) needs, so tenants only see their own hosts. Devices, links and topology
 * are shared infrastructure and are not filtered.
 *
 * <p>Shapes handled (onos-rest 2.7.0 HostsWebResource):</p>
 * <ul>
 *   <li>{@code GET /hosts}: {"hosts":[{"mac":...}, ...]} - other tenants' entries removed;</li>
 *   <li>{@code GET /hosts/{id}}, {@code GET /hosts/{mac}/{vlan}}: one host object -
 *       empty result (caller answers 404) unless its MAC belongs to the tenant.</li>
 * </ul>
 * Anything else throws IllegalStateException, so the proxy fails closed.
 */
public final class HostFilter {

    private HostFilter() {
    }

    /**
     * Filters a host list response in place.
     *
     * @param body   parsed {"hosts":[...]}
     * @param tenant caller's tenant
     * @return the same object with only the tenant's hosts
     */
    public static ObjectNode filterList(JsonNode body, Tenant tenant) {
        if (body == null || !body.isObject() || !body.path("hosts").isArray()) {
            throw new IllegalStateException("unexpected /hosts response shape");
        }
        ArrayNode kept = ((ObjectNode) body).arrayNode();
        for (JsonNode h : body.get("hosts")) {
            if (owned(h, tenant)) {
                kept.add(h);
            }
        }
        ((ObjectNode) body).set("hosts", kept);
        return (ObjectNode) body;
    }

    /**
     * Checks a single-host response.
     *
     * @param body   parsed host object
     * @param tenant caller's tenant
     * @return the host if owned, else empty
     */
    public static Optional<JsonNode> filterOne(JsonNode body, Tenant tenant) {
        if (body == null || !body.isObject()) {
            throw new IllegalStateException("unexpected /hosts/{id} response shape");
        }
        return owned(body, tenant) ? Optional.of(body) : Optional.empty();
    }

    private static boolean owned(JsonNode host, Tenant tenant) {
        String mac = host.path("mac").asText(null);
        if (mac == null) {
            return false;
        }
        try {
            return tenant.ownsMac(MacAddress.valueOf(mac));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
