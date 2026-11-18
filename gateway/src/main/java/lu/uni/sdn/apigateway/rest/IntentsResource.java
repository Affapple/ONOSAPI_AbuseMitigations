package lu.uni.sdn.apigateway.rest;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lu.uni.sdn.apigateway.GatewayService;
import lu.uni.sdn.apigateway.acl.AppIdImposer;
import lu.uni.sdn.apigateway.conflict.ConflictDetector;
import lu.uni.sdn.apigateway.acl.Ownership;
import lu.uni.sdn.apigateway.acl.Reasons;
import lu.uni.sdn.apigateway.quota.IntentFlowEstimator;
import lu.uni.sdn.apigateway.quota.QuotaManager;
import lu.uni.sdn.apigateway.quota.QuotaViolation;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantHost;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import lu.uni.sdn.apigateway.validation.PayloadValidator;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.ConnectivityIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentEvent;
import org.onosproject.net.intent.IntentListener;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.IntentState;
import org.onosproject.net.intent.Key;
import org.onosproject.net.topology.PathService;

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
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;

/**
 * /onos/gw/intents: intents submitted in-process with the tenant's ApplicationId.
 *
 * <pre>
 *   GET    /gw/intents[?tenant=]          the caller's tenant's intents (admin: all tenants)
 *   POST   /gw/intents[?tenant=]          one intent, as for POST /onos/v1/intents
 *   DELETE /gw/intents/{key}[?tenant=]    withdraw + purge, like DELETE /onos/v1/intents/{app}/{key}
 * </pre>
 * Because the key is looked up under the caller's own ApplicationId, a tenant can
 * only ever address its own intents. B3 quotas: 409 quota_intents / quota_flows,
 * 429 quota_churn. A new intent is checked with its estimated compiled flows
 * (IntentFlowEstimator) against the total and per-device flow quotas. An intent whose
 * withdraw times out (no purge) keeps counting until ONOS reports it WITHDRAWN.
 */
@Path("intents")
public class IntentsResource extends GatewayResource {

    private static final long WITHDRAW_TIMEOUT_SECONDS = 5;

    /**
     * Lists intents of the readable tenant app ids.
     *
     * @param tenant optional tenant id
     * @return 200 {"intents":[...]}
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("tenant") String tenant) {
        GatewayRequest r = gw();
        IntentService svc = get(IntentService.class);
        ObjectNode root = mapper().createObjectNode();
        ArrayNode arr = root.putArray("intents");
        for (ApplicationId app : readableApps(r, tenant)) {
            for (Intent i : svc.getIntentsByAppId(app)) {
                arr.add(codec(Intent.class).encode(i, this));
            }
        }
        return ok(root).build();
    }

    /**
     * Submits one intent for the caller's tenant.
     *
     * @param tenant optional tenant id (required for admins)
     * @param body   intent JSON
     * @return 201 {"appId","key","id"} with Location /onos/gw/intents/{key}
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@QueryParam("tenant") String tenant, InputStream body) {
        GatewayRequest r = gw();
        Tenant t = writeTenant(r, tenant);
        ApplicationId app = r.getSnapshot().appId(t.getId()).get();

        ObjectNode json = AppIdImposer.imposeOnIntent(readJson(body), app.name());
        if (r.getToggles().isValidationEnabled() && !isAdmin(r)) {
            // On the request JSON: the decoded HostToHostIntent always carries ONOS's own
            // default NOT_OPTICAL constraint, so only tenant-supplied constraints are refused.
            Optional<Ownership.Violation> cv = PayloadValidator.checkIntentJson(json);
            if (cv.isPresent()) {
                throw reject(BAD_REQUEST, cv.get().getReason(), cv.get().getMessage());
            }
        }
        Intent intent = codec(Intent.class).decode(json, this);
        if (intent == null) {
            throw reject(BAD_REQUEST, Reasons.BAD_REQUEST, "intent did not decode");
        }
        if (!app.equals(intent.appId())) {
            throw reject(INTERNAL_SERVER_ERROR, Reasons.APPID_NOT_IMPOSED,
                    "decoded intent has appId " + intent.appId() + ", expected " + app.name());
        }
        if (r.getToggles().isAclEnabled() && !isAdmin(r)) {
            Optional<Ownership.Violation> v = Ownership.checkIntent(intent, t);
            if (v.isPresent()) {
                Response.Status st = Reasons.UNSUPPORTED_INTENT_TYPE.equals(v.get().getReason())
                        ? BAD_REQUEST : FORBIDDEN;
                throw reject(st, v.get().getReason(), v.get().getMessage());
            }
        }
        // B4: payload validation (400), after the ACL endpoint checks (403)
        if (r.getToggles().isValidationEnabled() && !isAdmin(r)) {
            Optional<Ownership.Violation> v = PayloadValidator.checkIntent(intent, t, r.getValidationLimits());
            if (v.isPresent()) {
                throw reject(BAD_REQUEST, v.get().getReason(), v.get().getMessage());
            }
        }
        // B5 (409): spoofed sources / destination capture in the selector, foreign rewrites.
        // Overlap of the compiled flows is checked once the intent is INSTALLED (GatewayManager).
        if (r.getToggles().isConflictEnabled() && !isAdmin(r) && intent instanceof ConnectivityIntent) {
            ConnectivityIntent ci = (ConnectivityIntent) intent;
            Optional<Ownership.Violation> cv = ConflictDetector.checkSources(ci.selector(), t);
            if (!cv.isPresent()) {
                cv = ConflictDetector.checkDstCapture(ci.selector(), t, r.getSnapshot().registry());
            }
            if (!cv.isPresent()) {
                cv = ConflictDetector.checkRewrites(ci.treatment(), t);
            }
            if (cv.isPresent()) {
                throw reject(CONFLICT, cv.get().getReason(), cv.get().getMessage());
            }
        }
        QuotaManager qm = get(GatewayService.class).getQuotaManager();
        ReentrantLock lock = qm.lock(t.getId());
        lock.lock();
        try {
            Map<DeviceId, Integer> estimate = estimateFlows(intent, r);
            if (r.getToggles().isQuotaEnabled() && !isAdmin(r)) {
                Optional<QuotaViolation> qv = qm.checkIntentSubmit(t.getId(), app, intent.key(), estimate,
                        r.getQuotaLimits().forTenant(t));
                if (qv.isPresent()) {
                    throw quotaReject(qv.get());
                }
            }
            // B7: mark (by IntentId, unique per submission) before submitting
            get(IntentService.class).submit(intent);
            qm.recordIntentSubmit(t.getId(), intent.key(), estimate);
            if (!isAdmin(r)) {
                qm.recordChurn(t.getId(), 1);
            }
        } finally {
            lock.unlock();
        }

        String key = intent.key().toString();
        ObjectNode out = mapper().createObjectNode()
                .put("appId", app.name())
                .put("key", key)
                .put("id", intent.id().toString());
        return Response.created(URI.create("/onos/gw/intents/" + key)).entity(out).build();
    }

    /**
     * Withdraws and purges one of the caller's intents.
     *
     * @param key    intent key (string key, or 0x-hex / decimal long key)
     * @param tenant optional tenant id (required for admins)
     * @return 204, or 404 if the tenant has no such intent
     */
    @DELETE
    @Path("{key}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("key") String key, @QueryParam("tenant") String tenant) {
        GatewayRequest r = gw();
        Tenant t = writeTenant(r, tenant);
        ApplicationId app = r.getSnapshot().appId(t.getId()).get();
        IntentService svc = get(IntentService.class);

        Intent intent = svc.getIntent(Key.of(key, app));
        if (intent == null) {
            try {
                intent = svc.getIntent(Key.of(Long.decode(key), app));
            } catch (NumberFormatException e) {
                intent = null;
            }
        }
        if (intent == null) {
            throw reject(NOT_FOUND, Reasons.NOT_FOUND, "tenant " + t.getId() + " has no intent " + key);
        }
        if (!app.equals(intent.appId())) {
            throw reject(FORBIDDEN, Reasons.NOT_OWNER, "intent " + key + " does not belong to " + app.name());
        }

        Key k = intent.key();
        // B3: churn check under the tenant lock; the (up to 5 s) withdraw wait
        // below runs outside the lock so it does not block the tenant's other requests.
        QuotaManager qm = get(GatewayService.class).getQuotaManager();
        ReentrantLock lock = qm.lock(t.getId());
        lock.lock();
        try {
            if (r.getToggles().isQuotaEnabled() && !isAdmin(r)) {
                Optional<QuotaViolation> qv = qm.checkChurnOnly(t.getId(), 1, r.getQuotaLimits().forTenant(t));
                if (qv.isPresent()) {
                    throw quotaReject(qv.get());
                }
            }
            if (!isAdmin(r)) {
                qm.recordChurn(t.getId(), 1);
            }
            qm.forgetIntent(t.getId(), k);   // no phantom estimate after a fast delete
        } finally {
            lock.unlock();
        }
        CountDownLatch latch = new CountDownLatch(1);
        IntentListener listener = new WithdrawListener(k, latch);
        svc.addListener(listener);
        try {
            svc.withdraw(intent);
            try {
                latch.await(WITHDRAW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            IntentState state = svc.getIntentState(k);
            if (state == IntentState.WITHDRAWN || state == IntentState.FAILED) {
                svc.purge(intent);
            }
        } finally {
            svc.removeListener(listener);
        }
        return Response.noContent().build();
    }

    /**
     * Flows the intent is expected to compile to, per device (B3), from the path
     * between its endpoints. Host locations come from the HostService, falling back
     * to the tenant registry's static locations.
     */
    private Map<DeviceId, Integer> estimateFlows(Intent intent, GatewayRequest r) {
        HostService hosts = get(HostService.class);
        PathService paths = get(PathService.class);
        TenantRegistry reg = r.getSnapshot().registry();
        return IntentFlowEstimator.estimate(intent, id -> {
            Host h = hosts.getHost(id);
            if (h != null && h.location() != null) {
                return Optional.of((ConnectPoint) h.location());
            }
            return reg.tenants().values().stream().flatMap(x -> x.getHosts().stream())
                    .filter(x -> x.getMac().equals(id.mac())).map(TenantHost::getLocation).findFirst();
        }, paths::getPaths, reg.knownDevices());
    }

    /** Releases the latch when the intent reaches WITHDRAWN or FAILED. */
    private static final class WithdrawListener implements IntentListener {
        private final Key key;
        private final CountDownLatch latch;

        WithdrawListener(Key key, CountDownLatch latch) {
            this.key = key;
            this.latch = latch;
        }

        @Override
        public void event(IntentEvent event) {
            if (Objects.equals(event.subject().key(), key)
                    && (event.type() == IntentEvent.Type.WITHDRAWN || event.type() == IntentEvent.Type.FAILED)) {
                latch.countDown();
            }
        }
    }
}
