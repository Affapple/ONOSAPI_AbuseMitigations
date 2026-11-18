package lu.uni.sdn.apigateway.rest;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lu.uni.sdn.apigateway.GatewayService;
import lu.uni.sdn.apigateway.acl.Reasons;
import lu.uni.sdn.apigateway.audit.BypassAuditor;
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
import java.time.Instant;

import static javax.ws.rs.core.Response.Status.NOT_FOUND;

/**
 * /onos/gw/admin (admin only, enforced by GatewayFilter's ACL table).
 *
 * <pre>
 *   GET /gw/admin/tenants        quota limits and usage of every tenant
 *   GET  /gw/admin/tenants/{id}          same, one tenant (quotas, usage, conflicts, throttle)
 *   POST /gw/admin/tenants/{id}/unblock  reset the tenant's B6 score/level/cooldown
 *   GET  /gw/admin/audit                 B7 bypass events + rejection counters
 *   POST /gw/admin/audit/reset           reset the B7 counters
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
        QuotaManager qm = get(GatewayService.class).quotaManager();
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
        svc.conflicts(t.getId()).forEach(c::put);
        n.put("lastConflict", svc.lastConflict(t.getId()));
        n.set("throttle", throttle(svc, t.getId(), r));
        return n;
    }

    private ObjectNode throttle(GatewayService svc, String tenant, GatewayRequest r) {
        ThrottleSettings ts = svc.throttleSettings();
        ThrottleManager.Status st = svc.throttleManager().status(tenant, ts);
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
     * B7 audit: bypass events (flows/intents that did not come through the gateway) and
     * every gateway rejection per tenant, module and reason.
     *
     * @return 200 audit report
     */
    @GET
    @Path("audit")
    @Produces(MediaType.APPLICATION_JSON)
    public Response audit() {
        GatewayRequest r = gw();
        GatewayService svc = get(GatewayService.class);
        BypassAuditor.Snapshot a = svc.auditor().snapshot();
        ObjectNode n = mapper().createObjectNode();
        n.put("auditEnabled", r.getToggles().isAuditEnabled());
        ArrayNode allow = n.putArray("allowedApps");
        svc.auditAllowedApps().forEach(allow::add);
        n.put("bypassTotal", a.getTotal());
        ObjectNode bk = n.putObject("byKind");
        a.getByKind().forEach(bk::put);
        ObjectNode ba = n.putObject("byAppId");
        a.getByAppId().forEach(ba::put);
        ObjectNode bw = n.putObject("byReason");
        a.getByWhy().forEach(bw::put);
        ArrayNode recent = n.putArray("recent");
        for (BypassAuditor.Bypass b : a.getRecent()) {
            recent.addObject().put("time", Instant.ofEpochMilli(b.getEpochMillis()).toString()).put("kind", b.getKind())
                    .put("device", b.getDevice()).put("appId", b.getAppId()).put("id", b.getId())
                    .put("why", b.getWhy());
        }
        n.put("trackedGatewayFlows", a.getTrackedGatewayFlows());
        n.put("trackedGatewayIntents", a.getTrackedGatewayIntents());
        n.set("rejections", mapper().valueToTree(svc.rejections().snapshot()));
        return ok(n).build();
    }

    /**
     * Resets the B7 counters (bypass events and rejection counters), e.g. before a run.
     *
     * @return 204
     */
    @POST
    @Path("audit/reset")
    public Response auditReset() {
        gw();
        GatewayService svc = get(GatewayService.class);
        svc.auditor().reset();
        svc.rejections().reset();
        return Response.noContent().build();
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
        get(GatewayService.class).throttleManager().unblock(t.getId());
        return ok(status(r, t)).build();
    }
}
