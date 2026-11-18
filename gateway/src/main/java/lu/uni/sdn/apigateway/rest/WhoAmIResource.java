package lu.uni.sdn.apigateway.rest;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.onosproject.core.ApplicationId;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * GET /onos/gw/whoami: who the gateway thinks the caller is.
 */
@Path("whoami")
public class WhoAmIResource extends GatewayResource {

    /**
     * Returns the caller's identity as resolved by {@link GatewayFilter}.
     * Fails closed (500 no_identity) if the filter did not run.
     *
     * @return 200 with {user, roles, tenant, gatewayRole, appId}
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response whoami() {
        GatewayRequest r = gw();
        CallerIdentity id = r.getIdentity();
        ObjectNode root = mapper().createObjectNode();
        root.put("user", id.getUser());
        ArrayNode roles = root.putArray("roles");
        id.getRoles().forEach(roles::add);
        root.put("tenant", id.getTenantId());
        root.put("gatewayRole", id.getGatewayRole().label());
        root.put("appId", r.getSnapshot().appId(id.getTenantId()).map(ApplicationId::name).orElse(null));
        return ok(root).build();
    }
}
