package lu.uni.sdn.apigateway.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lu.uni.sdn.apigateway.GatewayService;
import lu.uni.sdn.apigateway.tenant.GatewayRole;
import org.onlab.osgi.DefaultServiceDirectory;
import org.onlab.osgi.ServiceNotFoundException;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import java.io.IOException;

/**
 * Feeds B6: every 4xx answer to a tenant caller that carries one of the gateway's
 * reason codes adds that reason's weight to the tenant's score (ThrottleManager.weightFor);
 * a bad_request on a POST/DELETE to /gw/flows or /gw/intents counts as validation.
 * Runs for every response, including requests aborted by GatewayFilter, so rejections of
 * every layer (ACL, B2, B3, B4, B5) are scored in one place. B6's own throttled_* answers
 * weigh 0 (see ThrottleManager). Admins and non-tenant callers are not scored. Every
 * rejection (any caller) is also counted per tenant/module/reason for /gw/admin/audit (B7).
 */
public class GatewayResponseFilter implements ContainerResponseFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext resp) throws IOException {
        int status = resp.getStatus();
        if (status < 400 || status >= 500) {
            return;
        }
        Object p = req.getProperty(GatewayFilter.IDENTITY_PROPERTY);
        if (!(p instanceof CallerIdentity)) {
            return;
        }
        CallerIdentity id = (CallerIdentity) p;
        Object entity = resp.getEntity();
        if (!(entity instanceof String)) {
            return;                       // proxied upstream bodies (byte[]) are not gateway rejections
        }
        String reason;
        try {
            JsonNode n = MAPPER.readTree((String) entity);
            reason = n == null ? null : n.path("reason").asText(null);
        } catch (IOException e) {
            return;
        }
        if (reason == null) {
            return;
        }
        GatewayService svc;
        try {
            svc = DefaultServiceDirectory.getService(GatewayService.class);
        } catch (ServiceNotFoundException e) {
            return;                       // gateway stopping
        }
        boolean tenantCaller = id.getTenantId() != null && id.getGatewayRole() != GatewayRole.ADMIN;
        svc.rejections().count(tenantCaller ? id.getTenantId() : null, reason);     // B7 counters: every rejection
        if (!tenantCaller) {
            return;
        }
        String m = req.getMethod();
        boolean write = m.equals("POST") || m.equals("DELETE");
        Object gr = req.getProperty(GatewayFilter.REQUEST_PROPERTY);
        boolean flowPath = gr instanceof GatewayRequest && !((GatewayRequest) gr).getSegments().isEmpty()
                && ("flows".equals(((GatewayRequest) gr).getSegments().get(0))
                    || "intents".equals(((GatewayRequest) gr).getSegments().get(0)));
        svc.recordViolation(id.getTenantId(), reason, write && flowPath);
    }
}
