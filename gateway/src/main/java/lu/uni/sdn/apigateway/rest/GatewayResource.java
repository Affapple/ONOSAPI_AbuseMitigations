package lu.uni.sdn.apigateway.rest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lu.uni.sdn.apigateway.acl.Reasons;
import lu.uni.sdn.apigateway.quota.QuotaViolation;
import lu.uni.sdn.apigateway.tenant.GatewayRole;
import lu.uni.sdn.apigateway.tenant.Tenant;
import org.onosproject.core.ApplicationId;
import org.onosproject.rest.AbstractWebResource;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Base class of the gateway resources. Every resource method starts with
 * {@link #gw()}, which fails closed (500 no_identity) if GatewayFilter did not
 * run or did not store its {@link GatewayRequest}.
 */
public abstract class GatewayResource extends AbstractWebResource {

    /** Request bodies are parsed with duplicate-key detection (same strictness as BatchCounter). */
    private static final ObjectMapper STRICT =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    @Context
    protected ContainerRequestContext requestContext;

    /**
     * Layer 1 result for this request.
     *
     * @return gateway request
     * @throws GatewayException 500 if the filter's property is missing
     */
    protected GatewayRequest gw() {
        Object p = requestContext == null ? null : requestContext.getProperty(GatewayFilter.REQUEST_PROPERTY);
        if (!(p instanceof GatewayRequest)) {
            throw new GatewayException(Response.Status.INTERNAL_SERVER_ERROR, Reasons.NO_IDENTITY,
                    "request did not pass through GatewayFilter");
        }
        return (GatewayRequest) p;
    }

    /**
     * Whether the caller is a gateway admin.
     *
     * @param r gateway request
     * @return true for ADMIN
     */
    protected static boolean isAdmin(GatewayRequest r) {
        return r.getIdentity().getGatewayRole() == GatewayRole.ADMIN;
    }

    /**
     * Tenant a write acts on. Tenant accounts always act on their own tenant
     * ({@code ?tenant=} may only name it). Admins must name one with {@code ?tenant=}.
     *
     * @param r           gateway request
     * @param tenantParam value of ?tenant= (may be null)
     * @return tenant
     */
    protected static Tenant writeTenant(GatewayRequest r, String tenantParam) {
        if (isAdmin(r)) {
            if (tenantParam == null) {
                throw new GatewayException(Response.Status.BAD_REQUEST, Reasons.TENANT_REQUIRED,
                        "admin writes must name the tenant with ?tenant=<id>");
            }
            return r.getSnapshot().registry().tenant(tenantParam).orElseThrow(() -> new GatewayException(
                    Response.Status.BAD_REQUEST, Reasons.TENANT_REQUIRED, "unknown tenant " + tenantParam));
        }
        String own = r.getIdentity().getTenantId();
        Tenant t = own == null ? null : r.getSnapshot().registry().tenant(own).orElse(null);
        if (t == null) {
            throw new GatewayException(Response.Status.FORBIDDEN, Reasons.UNKNOWN_ACCOUNT,
                    "caller is not a tenant account");
        }
        if (tenantParam != null && !tenantParam.equals(own)) {
            throw new GatewayException(Response.Status.FORBIDDEN, Reasons.TENANT_MISMATCH,
                    "tenant accounts can only act on their own tenant");
        }
        return t;
    }

    /**
     * ApplicationIds whose rules the caller may read. Tenant accounts: their own
     * (or every tenant's when aclEnabled=false, the reconnaissance setting).
     * Admins: every tenant, or the one named by ?tenant=.
     *
     * @param r           gateway request
     * @param tenantParam value of ?tenant= (may be null)
     * @return app ids
     */
    protected static Collection<ApplicationId> readableApps(GatewayRequest r, String tenantParam) {
        boolean all = isAdmin(r) || !r.getToggles().isAclEnabled();
        if (all) {
            if (tenantParam == null) {
                return r.getSnapshot().appIds().values();
            }
            return r.getSnapshot().appId(tenantParam).map(Collections::singletonList)
                    .orElseThrow(() -> new GatewayException(Response.Status.BAD_REQUEST,
                            Reasons.TENANT_REQUIRED, "unknown tenant " + tenantParam));
        }
        Tenant t = writeTenant(r, tenantParam);
        return Collections.singletonList(r.getSnapshot().appId(t.getId()).get());
    }

    /**
     * Parses a JSON request body.
     *
     * @param body request body
     * @return parsed JSON
     * @throws GatewayException 400 bad_request if it is empty, not JSON, or has duplicate keys
     */
    protected JsonNode readJson(InputStream body) {
        try {
            JsonNode n = body == null ? null : STRICT.readTree(body);
            if (n == null || n.isMissingNode()) {
                throw new GatewayException(Response.Status.BAD_REQUEST, Reasons.BAD_REQUEST, "empty request body");
            }
            return n;
        } catch (IOException e) {
            throw new GatewayException(Response.Status.BAD_REQUEST, Reasons.BAD_REQUEST,
                    "body is not valid JSON: " + e.getMessage());
        }
    }

    /**
     * Converts a quota violation into the exception that renders it.
     *
     * @param v violation
     * @return exception to throw
     */
    protected static GatewayException quotaReject(QuotaViolation v) {
        return new GatewayException(v.getStatus(), v.getReason(), v.getMessage(), v.getRetryAfterSeconds());
    }

    /**
     * Tenant owning a short ApplicationId (as carried by FlowRule.appId()).
     *
     * @param r     gateway request
     * @param appId short app id
     * @return tenant id, if a tenant app
     */
    protected static Optional<String> tenantOfShortAppId(GatewayRequest r, short appId) {
        return r.getSnapshot().appIds().entrySet().stream()
                .filter(e -> e.getValue().id() == appId).map(Map.Entry::getKey).findFirst();
    }

    /**
     * Shortcut for a 4xx/5xx rejection.
     *
     * @param status  status
     * @param reason  reason code
     * @param message detail
     * @return exception to throw
     */
    protected static GatewayException reject(Response.Status status, String reason, String message) {
        return new GatewayException(status, reason, message);
    }
}
