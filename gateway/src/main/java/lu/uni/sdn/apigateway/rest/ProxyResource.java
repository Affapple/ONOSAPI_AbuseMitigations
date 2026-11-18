package lu.uni.sdn.apigateway.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.io.ByteStreams;
import com.google.common.net.UrlEscapers;
import lu.uni.sdn.apigateway.ProxySettings;
import lu.uni.sdn.apigateway.acl.HostFilter;
import lu.uni.sdn.apigateway.acl.Reasons;
import lu.uni.sdn.apigateway.tenant.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static javax.ws.rs.core.Response.Status.BAD_GATEWAY;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;

/**
 * /onos/gw/v1/{path}: forwards to /onos/v1/{path}.
 *
 * <ul>
 *   <li>GET is forwarded with the viewer-only service account from tenants.json
 *       ("serviceAccount", password from component config). That account cannot
 *       write, so a bug here cannot become a write path.</li>
 *   <li>POST/PUT/DELETE (admin only, enforced by GatewayFilter) are forwarded
 *       with the caller's own Authorization header. The gateway stores no admin
 *       credential.</li>
 * </ul>
 * For tenant callers (ACL on), GET /hosts[/...] is filtered to the caller's own
 * hosts (HostFilter); another tenant's single host answers 404 like a missing one.
 * Which paths a role may use is decided in GatewayFilter (PathAcl), before this
 * resource is reached. Upstream 401/403 become 502 upstream_error, so a client
 * never mistakes a service-account problem for its own credentials being wrong.
 */
@Path("v1")
public class ProxyResource extends GatewayResource {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 15000;

    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * Forwards a GET.
     *
     * @return upstream response
     */
    @GET
    @Path("{path: .+}")
    public Response get() {
        return forward("GET", null, false);
    }

    /**
     * Forwards a POST (admin only).
     *
     * @param body request body
     * @return upstream response
     */
    @POST
    @Path("{path: .+}")
    public Response post(InputStream body) {
        return forward("POST", body, true);
    }

    /**
     * Forwards a PUT (admin only).
     *
     * @param body request body
     * @return upstream response
     */
    @PUT
    @Path("{path: .+}")
    public Response put(InputStream body) {
        return forward("PUT", body, true);
    }

    /**
     * Forwards a DELETE (admin only).
     *
     * @return upstream response
     */
    @DELETE
    @Path("{path: .+}")
    public Response delete() {
        return forward("DELETE", null, true);
    }

    private Response forward(String method, InputStream body, boolean asCaller) {
        GatewayRequest r = gw();
        List<String> segs = r.getSegments();
        if (segs.size() < 2 || !segs.get(0).equals("v1")) {
            throw reject(INTERNAL_SERVER_ERROR, Reasons.INTERNAL_ERROR, "unexpected proxy path " + segs);
        }
        if (asCaller && !isAdmin(r)) {
            // GatewayFilter already denies this; fail closed if that ever changes.
            throw reject(Response.Status.FORBIDDEN, Reasons.ACL_DENIED, "only admins may write through /gw/v1");
        }
        ProxySettings ps = r.getProxy();
        String path = segs.subList(1, segs.size()).stream()
                .map(UrlEscapers.urlPathSegmentEscaper()::escape)
                .collect(Collectors.joining("/"));
        String query = requestContext.getUriInfo().getRequestUri().getRawQuery();
        String target = ps.getBaseUrl() + path + (query == null ? "" : "?" + query);

        String auth;
        if (asCaller) {
            auth = requestContext.getHeaderString("Authorization");
        } else {
            String svc = r.getSnapshot().registry().serviceAccount();
            if (svc == null) {
                throw reject(BAD_GATEWAY, Reasons.UPSTREAM_ERROR, "no serviceAccount in the tenant registry");
            }
            auth = "Basic " + Base64.getEncoder().encodeToString(
                    (svc + ":" + ps.getServiceAccountPassword()).getBytes(StandardCharsets.UTF_8));
        }

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(target).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            if (auth != null) {
                c.setRequestProperty("Authorization", auth);
            }
            c.setRequestProperty("Accept", "application/json");
            if (body != null && (method.equals("POST") || method.equals("PUT"))) {
                String ct = requestContext.getHeaderString("Content-Type");
                c.setRequestProperty("Content-Type", ct == null ? "application/json" : ct);
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    ByteStreams.copy(body, os);
                }
            }
            int code = c.getResponseCode();
            if (!asCaller && (code == 401 || code == 403)) {
                log.warn("Upstream rejected the service account for GET {}: HTTP {}", target, code);
                throw reject(BAD_GATEWAY, Reasons.UPSTREAM_ERROR,
                        "upstream rejected the gateway service account (HTTP " + code + ")");
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] bytes = in == null ? new byte[0] : readAll(in);
            if (method.equals("GET") && segs.get(1).equals("hosts")
                    && !isAdmin(r) && r.getToggles().isAclEnabled()) {
                return hostsForTenant(r, segs, code, bytes);
            }
            if (code >= 400) {
                bytes = withReason(bytes, code);
            }
            Response.ResponseBuilder rb = Response.status(code);
            if (bytes.length > 0) {
                rb.entity(bytes);
                if (c.getContentType() != null) {
                    rb.type(c.getContentType());
                }
            }
            return rb.build();
        } catch (IOException e) {
            throw reject(BAD_GATEWAY, Reasons.UPSTREAM_ERROR, "upstream request failed: " + e.getMessage());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * Tenant view of /hosts: only hosts whose MAC belongs to the caller's tenant.
     * For a single host, "another tenant's host" and "no such host" give the same
     * 404 body, so the answer is not an existence oracle. Unparseable upstream
     * JSON fails closed (502).
     */
    private Response hostsForTenant(GatewayRequest r, List<String> segs, int code, byte[] bytes) {
        Tenant t = writeTenant(r, null);
        boolean list = segs.size() == 2;
        if (!list && code == 404) {
            return hostNotFound();
        }
        if (code != 200) {
            return Response.status(code).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(withReason(bytes, code)).build();
        }
        try {
            JsonNode body = mapper().readTree(bytes);
            if (list) {
                return ok(HostFilter.filterList(body, t)).build();
            }
            Optional<JsonNode> one = HostFilter.filterOne(body, t);
            return one.isPresent() ? ok(one.get()).build() : hostNotFound();
        } catch (IOException | IllegalStateException e) {
            throw reject(BAD_GATEWAY, Reasons.UPSTREAM_ERROR, "cannot filter upstream hosts response: "
                    + e.getMessage());
        }
    }

    private static Response hostNotFound() {
        return Errors.response(404, Reasons.NOT_FOUND, "host not found");
    }

    /**
     * Adds "reason": "upstream_rejected" to an upstream JSON error object that has
     * no reason code, so proxied errors follow the gateway's error format too.
     */
    private byte[] withReason(byte[] bytes, int code) {
        try {
            JsonNode n = mapper().readTree(bytes);
            if (n != null && n.isObject() && !n.has("reason")) {
                ((ObjectNode) n).put("reason", Reasons.UPSTREAM_REJECTED);
                return mapper().writeValueAsBytes(n);
            }
        } catch (IOException e) {
            log.debug("upstream {} error body is not JSON", code);
        }
        return bytes;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            return ByteStreams.toByteArray(i);
        }
    }
}
