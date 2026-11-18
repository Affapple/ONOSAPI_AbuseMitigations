package lu.uni.sdn.apigateway.rest;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lu.uni.sdn.apigateway.GatewayService;
import lu.uni.sdn.apigateway.acl.Reasons;
import lu.uni.sdn.apigateway.quota.QuotaLimits;
import lu.uni.sdn.apigateway.quota.QuotaManager;
import lu.uni.sdn.apigateway.quota.Usage;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.throttle.ThrottleManager;
import lu.uni.sdn.apigateway.throttle.ThrottleSettings;
import org.onosproject.core.ApplicationId;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static javax.ws.rs.core.Response.Status.NOT_FOUND;

/**
 * /onos/gw/admin (admin only, enforced by GatewayFilter's ACL table).
 *
 * <pre>
 *   GET /gw/admin/tenants        quota limits and usage of every tenant
 *   GET  /gw/admin/tenants/{id}          same, one tenant (quotas, usage, conflicts, throttle)
 *   POST /gw/admin/tenants/{id}/unblock  reset the tenant's B6 score/level/cooldown
 * </pre>
 */
@Path("admin")
public class AdminResource extends GatewayResource {


    /**
     * All tenants' quota status.
     *
     * @return 200 {"tenants": {id: status}}
     */
    @GET
    @Path("tenants")
    @Produces(MediaType.APPLICATION_JSON)
    public Response tenants() {
        GatewayRequest r = gw();
        ObjectNode root = mapper().createObjectNode();
        ObjectNode all = root.putObject("tenants");
        for (Tenant t : r.getSnapshot().registry().tenants().values()) {
            all.set(t.getId(), status(r, t));
        }
        return ok(root).build();
    }

    /**
     * One tenant's quota status.
     *
     * @param id tenant id
     * @return 200 status, 404 if unknown
     */
    @GET
    @Path("tenants/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response tenant(@PathParam("id") String id) {
        GatewayRequest r = gw();
        Tenant t = r.getSnapshot().registry().tenant(id)
                .orElseThrow(() -> reject(NOT_FOUND, Reasons.NOT_FOUND, "no tenant " + id));
        return ok(status(r, t)).build();
    }

    private ObjectNode status(GatewayRequest r, Tenant t) {
        QuotaManager qm = get(GatewayService.class).getQuotaManager();
        ApplicationId app = r.getSnapshot().appId(t.getId()).get();
        QuotaLimits lim = r.getQuotaLimits().forTenant(t);
        Usage u = qm.usage(t.getId(), app);
        ObjectNode n = mapper().createObjectNode();
        n.put("appId", app.name());
        n.put("quotaEnabled", r.getToggles().isQuotaEnabled());
        ObjectNode q = n.putObject("quotas");
        q.put(QuotaLimits.MAX_FLOWS, lim.getMaxFlowsPerTenant());
        q.put(QuotaLimits.MAX_FLOWS_PER_DEVICE, lim.getMaxFlowsPerTenantPerDevice());
        q.put(QuotaLimits.MAX_INTENTS, lim.getMaxIntentsPerTenant());
        q.put(QuotaLimits.CHURN_LIMIT, lim.getChurnLimit());
        q.put("churnWindowSeconds", lim.getChurnWindowSeconds());
        ObjectNode us = n.putObject("usage");
        us.put("flows", u.totalFlows());
        us.put("directFlows", u.getDirectFlowIds().size());
        us.put("intentFlows", u.getIntentFlows());
        us.put("estimatedIntentFlows", u.getEstimatedIntentFlows());
        ObjectNode pd = us.putObject("perDevice");
        u.getPerDevice().forEach((d, c) -> pd.put(d.toString(), c));
        us.put("intents", u.getIntentKeys().size());
        us.put("churn", qm.churn(t.getId(), lim.getChurnWindowSeconds()));
        GatewayService svc = get(GatewayService.class);
        ObjectNode c = n.putObject("conflicts");
        svc.getRejections().of(t.getId(), "conflict").forEach(c::put);
        n.put("lastConflict", svc.getRejections().last(t.getId(), "conflict"));
        n.set("throttle", throttle(svc, t.getId(), r));
        return n;
    }

    private ObjectNode throttle(GatewayService svc, String tenant, GatewayRequest r) {
        ThrottleSettings ts = svc.getThrottleSettings();
        ThrottleManager.Status st = svc.getThrottleManager().status(tenant, ts);
        ObjectNode n = mapper().createObjectNode();
        n.put("adaptiveEnabled", r.getToggles().isAdaptiveEnabled());
        n.put("mode", ts.getMode());
        n.put("score", Math.round(st.getScore() * 100) / 100.0);
        n.put("level", st.getLevel());
        double factor = ts.isFixed()
                ? (st.getFixedRemainingSeconds() > 0 && !ts.isFixedBlockWrites() ? ts.getFixedRateFactor() : 1.0)
                : (st.getLevel() >= 1 ? ts.getRestrictedRateFactor() : 1.0);
        n.put("rateFactor", factor);
        n.put("fixedEffect", ts.getFixedEffect());
        n.put("cooldownRemaining", st.getCooldownRemainingSeconds());
        n.put("cooldownEntries", st.getCooldownEntries());
        n.put("fixedRemaining", st.getFixedRemainingSeconds());
        ArrayNode h = n.putArray("history");
        long now = System.nanoTime();
        for (ThrottleManager.Event e : st.getHistory()) {
            h.addObject().put("secondsAgo", Math.round((now - e.getAtNanos()) / 1e8) / 10.0)
                    .put("reason", e.getReason()).put("weight", e.getWeight())
                    .put("scoreAfter", Math.round(e.getScoreAfter() * 100) / 100.0)
                    .put("levelAfter", e.getLevelAfter());
        }
        return n;
    }

    /**
     * Admin unblock (B6): resets the tenant's score, level, cooldown and fixed penalty.
     *
     * @param id tenant id
     * @return 200 with the tenant's status, 404 if unknown
     */
    @POST
    @Path("tenants/{id}/unblock")
    @Produces(MediaType.APPLICATION_JSON)
    public Response unblock(@PathParam("id") String id) {
        GatewayRequest r = gw();
        Tenant t = r.getSnapshot().registry().tenant(id)
                .orElseThrow(() -> reject(NOT_FOUND, Reasons.NOT_FOUND, "no tenant " + id));
        get(GatewayService.class).getThrottleManager().unblock(t.getId());
        return ok(status(r, t)).build();
    }
}
