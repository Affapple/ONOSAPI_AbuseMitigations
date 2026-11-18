package lu.uni.sdn.apigateway.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lu.uni.sdn.apigateway.GatewayService;
import lu.uni.sdn.apigateway.acl.AppIdImposer;
import lu.uni.sdn.apigateway.conflict.ConflictDetector;
import lu.uni.sdn.apigateway.conflict.TenantRules;
import lu.uni.sdn.apigateway.acl.Ownership;
import lu.uni.sdn.apigateway.acl.Reasons;
import lu.uni.sdn.apigateway.quota.QuotaManager;
import lu.uni.sdn.apigateway.quota.QuotaViolation;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import lu.uni.sdn.apigateway.validation.PayloadValidator;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.intent.IntentService;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;

/**
 * /onos/gw/flows: flow rules installed in-process with the tenant's ApplicationId.
 *
 * <pre>
 *   GET    /gw/flows[?tenant=]            the caller's tenant's flows (admin: all tenants)
 *   POST   /gw/flows[?tenant=]            {"flows":[...]} as for POST /onos/v1/flows
 *   DELETE /gw/flows                      {"flows":[{"deviceId":..,"flowId":..}]}
 *   DELETE /gw/flows/{deviceId}/{flowId}
 * </pre>
 * Writes are all-or-nothing: if any flow fails a check (ownership, B3 quota), nothing
 * is installed/removed. Order: B1 ownership 403, B4 validation 400, B5 conflict 409
 * (spoofed_source, foreign_rewrite, dst_capture, overlap_conflict), B3 quota.
 * Quota rejections: 409 quota_flows / quota_flows_device,
 * 429 quota_churn (with Retry-After).
 * ONOS applies flow adds/removes asynchronously: a flow returned by POST may not be
 * visible (and deletable) for a short moment; DELETE then answers 404 not_found.
 */
@Path("flows")
public class FlowsResource extends GatewayResource {

    /**
     * Lists flows owned by the readable tenant app ids.
     *
     * @param tenant optional tenant id
     * @return 200 {"flows":[...]}
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("tenant") String tenant) {
        GatewayRequest r = gw();
        FlowRuleService svc = get(FlowRuleService.class);
        ObjectNode root = mapper().createObjectNode();
        ArrayNode arr = root.putArray("flows");
        for (ApplicationId app : readableApps(r, tenant)) {
            for (FlowEntry e : svc.getFlowEntriesById(app)) {
                arr.add(codec(FlowEntry.class).encode(e, this));
            }
        }
        return ok(root).build();
    }

    /**
     * Installs flows for the caller's tenant.
     *
     * @param tenant optional tenant id (required for admins)
     * @param body   {"flows":[...]}
     * @return 200 {"flows":[{"deviceId","flowId"}], "appId"}
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@QueryParam("tenant") String tenant, InputStream body) {
        GatewayRequest r = gw();
        Tenant t = writeTenant(r, tenant);
        ApplicationId app = r.getSnapshot().appId(t.getId()).get();

        ArrayNode flowsJson = AppIdImposer.imposeOnFlows(readJson(body), app.name());
        verifyCharged(r, flowsJson.size());
        List<FlowRule> rules = codec(FlowRule.class).decode(flowsJson, this);
        verifyCharged(r, rules.size());
        for (FlowRule rule : rules) {
            if (rule.appId() != app.id()) {
                throw reject(INTERNAL_SERVER_ERROR, Reasons.APPID_NOT_IMPOSED,
                        "decoded flow has appId " + rule.appId() + ", expected " + app.name());
            }
        }
        if (r.getToggles().isAclEnabled() && !isAdmin(r)) {
            for (FlowRule rule : rules) {
                Optional<Ownership.Violation> v = Ownership.checkFlow(rule, t, r.getSnapshot().registry());
                if (v.isPresent()) {
                    throw reject(FORBIDDEN, v.get().getReason(), v.get().getMessage());
                }
            }
        }
        // B4: payload validation (400), after the ACL ownership checks (403)
        if (r.getToggles().isValidationEnabled() && !isAdmin(r)) {
            for (FlowRule rule : rules) {
                Optional<Ownership.Violation> v = PayloadValidator.checkFlow(rule, t, r.getValidationLimits());
                if (v.isPresent()) {
                    throw reject(BAD_REQUEST, v.get().getReason(), v.get().getMessage());
                }
            }
        }
        // B5: spoofing, foreign rewrites, destination capture, overlap with other tenants (409)
        if (r.getToggles().isConflictEnabled() && !isAdmin(r)) {
            Optional<Ownership.Violation> cv = conflicts(r, t, rules);
            if (cv.isPresent()) {
                throw reject(CONFLICT, cv.get().getReason(), cv.get().getMessage());
            }
        }
        // B3: check -> apply -> record under the tenant's lock (no two requests of one
        // tenant can both pass a check only one of them fits).
        QuotaManager qm = get(GatewayService.class).getQuotaManager();
        ReentrantLock lock = qm.lock(t.getId());
        lock.lock();
        try {
            if (r.getToggles().isQuotaEnabled() && !isAdmin(r)) {
                Optional<QuotaViolation> qv = qm.checkFlowAdds(t.getId(), app, rules,
                        r.getQuotaLimits().forTenant(t));
                if (qv.isPresent()) {
                    throw quotaReject(qv.get());
                }
            }
            get(FlowRuleService.class).applyFlowRules(rules.toArray(new FlowRule[0]));
            qm.recordFlowAdds(t.getId(), rules);
            if (!isAdmin(r)) {
                qm.recordChurn(t.getId(), rules.size());
            }
        } finally {
            lock.unlock();
        }

        ObjectNode root = mapper().createObjectNode();
        root.put("appId", app.name());
        ArrayNode out = root.putArray("flows");
        rules.forEach(rule -> out.addObject()
                .put("deviceId", rule.deviceId().toString())
                .put("flowId", Long.toString(rule.id().value())));
        return ok(root).build();
    }

    /**
     * Removes one flow, if owned by the caller's tenant.
     *
     * @param deviceId device id
     * @param flowId   flow id (decimal, or 0x-hex)
     * @return 204
     */
    @DELETE
    @Path("{deviceId}/{flowId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteOne(@PathParam("deviceId") String deviceId, @PathParam("flowId") String flowId) {
        GatewayRequest r = gw();
        FlowEntry e = resolveOwned(r, deviceId, flowId);
        remove(r, Collections.singletonList(e));
        return Response.noContent().build();
    }

    /**
     * Removes a batch of flows; every one must exist and be owned.
     *
     * @param body {"flows":[{"deviceId","flowId"}]}
     * @return 204
     */
    @DELETE
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteBatch(InputStream body) {
        GatewayRequest r = gw();
        JsonNode root = readJson(body);
        if (!root.path("flows").isArray() || root.get("flows").size() == 0) {
            throw reject(BAD_REQUEST, Reasons.BAD_REQUEST, "body must be {\"flows\": [{\"deviceId\",\"flowId\"}]}");
        }
        verifyCharged(r, root.get("flows").size());
        List<FlowEntry> toRemove = new ArrayList<>();
        for (JsonNode n : root.get("flows")) {
            toRemove.add(resolveOwned(r, n.path("deviceId").asText(null), n.path("flowId").asText(null)));
        }
        remove(r, toRemove);
        return Response.noContent().build();
    }

    /** B5 checks of a batch: per-rule spoofing/rewrites/capture, then overlap with other tenants. */
    private Optional<Ownership.Violation> conflicts(GatewayRequest r, Tenant t, List<FlowRule> rules) {
        TenantRegistry reg = r.getSnapshot().registry();
        for (FlowRule rule : rules) {
            Optional<Ownership.Violation> v = ConflictDetector.checkSources(rule.selector(), t);
            if (!v.isPresent()) {
                v = ConflictDetector.checkRewrites(rule.treatment(), t);
            }
            if (!v.isPresent()) {
                v = ConflictDetector.checkDstCapture(rule.selector(), t, reg);
            }
            if (v.isPresent()) {
                return v;
            }
        }
        Map<DeviceId, List<ConflictDetector.OwnedRule>> existing =
                TenantRules.collect(r.getSnapshot(), get(FlowRuleService.class), get(IntentService.class));
        for (FlowRule rule : rules) {
            Optional<Ownership.Violation> v = ConflictDetector.checkOverlap(rule, t.getId(), reg,
                    existing.getOrDefault(rule.deviceId(), Collections.emptyList()), ConflictDetector.OVERLAP_CONFLICT);
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    /**
     * Removes flows. A tenant caller is charged one churn operation per flow (checked
     * against churnLimit first, under its tenant lock); every removed flow is entered
     * in its owner tenant's pending ledger so quota counts drop immediately.
     */
    private void remove(GatewayRequest r, List<FlowEntry> entries) {
        QuotaManager qm = get(GatewayService.class).getQuotaManager();
        String caller = isAdmin(r) ? null : r.getIdentity().getTenantId();
        ReentrantLock lock = caller == null ? null : qm.lock(caller);
        if (lock != null) {
            lock.lock();
        }
        try {
            if (caller != null && r.getToggles().isQuotaEnabled()) {
                Tenant t = r.getSnapshot().registry().tenant(caller).orElse(null);
                Optional<QuotaViolation> qv = qm.checkChurnOnly(caller, entries.size(),
                        r.getQuotaLimits().forTenant(t));
                if (qv.isPresent()) {
                    throw quotaReject(qv.get());
                }
            }
            get(FlowRuleService.class).removeFlowRules(entries.toArray(new FlowEntry[0]));
            Map<String, List<FlowEntry>> byOwner = new HashMap<>();
            for (FlowEntry e : entries) {
                tenantOfShortAppId(r, e.appId())
                        .ifPresent(o -> byOwner.computeIfAbsent(o, k -> new ArrayList<>()).add(e));
            }
            byOwner.forEach(qm::recordFlowRemoves);
            if (caller != null) {
                qm.recordChurn(caller, entries.size());
            }
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    /**
     * Defense in depth for B2: the number of flows acted on must equal the number
     * the rate limiter counted and charged in the filter. Otherwise 400, nothing done.
     */
    private static void verifyCharged(GatewayRequest r, int actual) {
        Integer charged = r.getChargedFlows();
        if (charged != null && charged != actual) {
            throw reject(BAD_REQUEST, Reasons.BATCH_MISMATCH, "body has " + actual
                    + " flows but " + charged + " were counted for the rate limit");
        }
    }

    /**
     * Finds a flow entry and checks it belongs to the caller's tenant (ACL
     * ownership: DELETE only on rules with the caller's ApplicationId).
     */
    private FlowEntry resolveOwned(GatewayRequest r, String deviceId, String flowId) {
        if (deviceId == null || flowId == null) {
            throw reject(BAD_REQUEST, Reasons.BAD_REQUEST, "deviceId and flowId are required");
        }
        long id;
        try {
            id = Long.decode(flowId);
        } catch (NumberFormatException ex) {
            throw reject(BAD_REQUEST, Reasons.BAD_REQUEST, "flowId must be a number: " + flowId);
        }
        DeviceId dev = DeviceId.deviceId(deviceId);
        FlowEntry found = null;
        for (FlowEntry e : get(FlowRuleService.class).getFlowEntries(dev)) {
            // A flow already being removed counts as gone (a repeated DELETE gets 404).
            if (e.id().value() == id && e.state() != FlowEntry.FlowEntryState.PENDING_REMOVE
                    && e.state() != FlowEntry.FlowEntryState.REMOVED) {
                found = e;
                break;
            }
        }
        if (found == null) {
            throw reject(NOT_FOUND, Reasons.NOT_FOUND, "no flow " + flowId + " on " + deviceId);
        }
        if (isAdmin(r)) {
            return found;
        }
        if (!r.getToggles().isAclEnabled()) {
            // Ownership between tenants is off, but core/other-app flows stay admin-only.
            short foundApp = found.appId();
            boolean tenantFlow = r.getSnapshot().appIds().values().stream()
                    .anyMatch(a -> a.id() == foundApp);
            if (!tenantFlow) {
                throw reject(FORBIDDEN, Reasons.NOT_OWNER, "flow " + flowId + " on " + deviceId
                        + " does not belong to any tenant");
            }
            return found;
        }
        Tenant t = writeTenant(r, null);
        ApplicationId own = r.getSnapshot().appId(t.getId()).get();
        if (found.appId() != own.id()) {
            throw reject(FORBIDDEN, Reasons.NOT_OWNER, "flow " + flowId + " on " + deviceId
                    + " does not belong to tenant " + t.getId());
        }
        return found;
    }
}
