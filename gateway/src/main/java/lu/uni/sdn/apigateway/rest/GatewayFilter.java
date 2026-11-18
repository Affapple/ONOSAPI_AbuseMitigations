package lu.uni.sdn.apigateway.rest;

import com.google.common.io.ByteStreams;
import lu.uni.sdn.apigateway.GatewayService;
import lu.uni.sdn.apigateway.GatewaySnapshot;
import lu.uni.sdn.apigateway.Limits;
import lu.uni.sdn.apigateway.ModuleToggles;
import lu.uni.sdn.apigateway.acl.PathAcl;
import lu.uni.sdn.apigateway.acl.PathSanitizer;
import lu.uni.sdn.apigateway.acl.Reasons;
import lu.uni.sdn.apigateway.ratelimit.BatchCounter;
import lu.uni.sdn.apigateway.ratelimit.TokenBucketLimiter;
import lu.uni.sdn.apigateway.tenant.GatewayRole;
import lu.uni.sdn.apigateway.throttle.ThrottleManager;
import org.onlab.osgi.DefaultServiceDirectory;
import org.onlab.osgi.ServiceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Layer 1 of the gateway. {@code @PreMatching}, so it runs on every request that
 * reaches Jersey, including ones that would end in 404/405, before any resource
 * is selected.
 *
 * <p>Order:</p>
 * <ol>
 *   <li>capture one snapshot (registry + app ids), toggles and limits; identify the caller;</li>
 *   <li>sanitize the path (the error is reported after step 3, so bad paths cost tokens);</li>
 *   <li>B6 adaptive throttling (tenant callers, if {@code adaptiveEnabled}): 403
 *       throttled_blocked, 429 throttled_cooldown / throttled_fixed, or a reduced B2
 *       refill rate while restricted;</li>
 *   <li>B2 rate limit (if {@code rateLimitEnabled}; admins are exempt from the limiter and the
 *       caps on every path, including /gw/v1 writes): body size cap
 *       ({@code maxBodyBytes}, before any parsing), batch size cap ({@code maxBatchSize},
 *       streaming count before decoding), then the tenant's token bucket with weighted cost
 *       (1 per request; 1 per flow for POST/DELETE /flows). Rejected-by-cap requests cost 1.
 *       Every request is charged here, including ones the ACL will deny or that will 404;</li>
 *   <li>the role x method x path table (default deny);</li>
 *   <li>store a {@link GatewayRequest} as request property {@link #REQUEST_PROPERTY}.</li>
 * </ol>
 *
 * <p>No {@code @Priority}: the bundle would then import javax.annotation 3.x, but
 * ONOS 2.7's Karaf only exports javax.annotation 1.3.0. It is the only filter.</p>
 */
@PreMatching
public class GatewayFilter implements ContainerRequestFilter {

    /** Request property holding the {@link GatewayRequest}. */
    public static final String REQUEST_PROPERTY = "lu.uni.sdn.gw.request";

    /** Request property holding the caller's {@link CallerIdentity} (set before any check). */
    public static final String IDENTITY_PROPERTY = "lu.uni.sdn.gw.identity";

    /** Marker: the limiter did not count flows for this request. */
    private static final int NOT_COUNTED = Integer.MIN_VALUE;

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        GatewayService svc;
        try {
            svc = DefaultServiceDirectory.getService(GatewayService.class);
        } catch (ServiceNotFoundException e) {
            ctx.abortWith(Errors.response(503, Reasons.UNAVAILABLE, "gateway service not available"));
            return;
        }
        GatewaySnapshot snap = svc.snapshot();
        ModuleToggles toggles = svc.toggles();
        CallerIdentity id = CallerIdentity.resolve(ctx.getSecurityContext(), snap.registry());
        String method = ctx.getMethod();
        String rawPath = ctx.getUriInfo().getPath(false);

        List<String> segments = null;
        String pathError = null;
        try {
            segments = PathSanitizer.segments(rawPath);
        } catch (IllegalArgumentException e) {
            pathError = e.getMessage();
        }

        ctx.setProperty(IDENTITY_PROPERTY, id);      // for GatewayResponseFilter (B6 scoring)

        // B6: adaptive throttling, after identification and before B2. Tenant callers only.
        double rateFactor = 1.0;
        if (toggles.isAdaptiveEnabled() && id.getTenantId() != null && id.getGatewayRole() != GatewayRole.ADMIN) {
            boolean write = method.equals("POST") || method.equals("PUT") || method.equals("DELETE");
            ThrottleManager.Decision td = svc.throttleManager().check(id.getTenantId(), write, svc.throttleSettings());
            if (!td.isAllowed()) {
                log.debug("DENY {} {} /{} tenant={}: {}", td.getStatus(), method, rawPath, id.getTenantId(),
                          td.getReason());
                ctx.abortWith(Response.fromResponse(Errors.response(td.getStatus(), td.getReason(), td.getMessage()))
                        .header("Retry-After", Long.toString(td.getRetryAfterSeconds())).build());
                return;
            }
            rateFactor = td.getRateFactor();
        }

        Integer chargedFlows = null;
        // Admins are exempt from the limiter AND the size caps, on every path including
        // /gw/v1 writes: they are trusted and can use /onos/v1 directly anyway.
        if (toggles.isRateLimitEnabled() && id.getGatewayRole() != GatewayRole.ADMIN) {
            int[] counted = {NOT_COUNTED};
            if (!rateLimit(ctx, svc.limits(), svc.rateLimiter(), id, method, segments, rawPath, counted,
                           rateFactor)) {
                return;
            }
            chargedFlows = counted[0] == NOT_COUNTED ? null : counted[0];
        }

        if (pathError != null) {
            reject(ctx, 400, Reasons.BAD_PATH, pathError, id, method, rawPath);
            return;
        }
        PathAcl.Decision d = PathAcl.decide(id.getGatewayRole(), method, segments, toggles.isAclEnabled());
        if (!d.isAllowed()) {
            reject(ctx, 403, d.getReason(), d.getMessage(), id, method, rawPath);
            return;
        }
        ctx.setProperty(REQUEST_PROPERTY,
                new GatewayRequest(id, snap, toggles, svc.proxySettings(), svc.quotaLimits(),
                                   svc.validationLimits(), segments, chargedFlows));
        log.debug("ALLOW {} /{} user={} role={} tenant={}", method, rawPath,
                  id.getUser(), id.getGatewayRole().label(), id.getTenantId());
    }

    /**
     * B2. Returns false (and aborts the request) on 413 or 429.
     */
    private boolean rateLimit(ContainerRequestContext ctx, Limits lim, TokenBucketLimiter limiter,
                              CallerIdentity id, String method, List<String> segments, String rawPath,
                              int[] countedOut, double rateFactor) throws IOException {
        int cost = 1;
        String capReason = null;
        String capMessage = null;

        boolean hasBody = method.equals("POST") || method.equals("PUT") || method.equals("DELETE");
        if (hasBody && ctx.hasEntity()) {
            byte[] body = boundedBody(ctx, lim.getMaxBodyBytes());
            if (body == null) {
                capReason = Reasons.BODY_TOO_LARGE;
                capMessage = "request body exceeds maxBodyBytes=" + lim.getMaxBodyBytes();
            } else {
                ctx.setEntityStream(new ByteArrayInputStream(body));
                boolean flowBatch = segments != null && segments.size() == 1 && segments.get(0).equals("flows")
                        && (method.equals("POST") || method.equals("DELETE"));
                if (flowBatch) {
                    int n = BatchCounter.countFlows(body);
                    countedOut[0] = n;
                    if (n > lim.getMaxBatchSize()) {
                        capReason = Reasons.BATCH_TOO_LARGE;
                        capMessage = n + " flows in one request exceed maxBatchSize=" + lim.getMaxBatchSize();
                    } else {
                        cost = Math.max(1, n);
                    }
                }
            }
        }
        if (capReason != null) {
            cost = 1;
        }

        String key = id.getTenantId() != null ? "tenant:" + id.getTenantId()
                : "user:" + (id.getUser() == null ? "-" : id.getUser());
        TokenBucketLimiter.Result r = limiter.tryAcquire(key, cost, lim.getRateLimitRps() * rateFactor,
                lim.getRateLimitBurst());
        if (!r.isAllowed()) {
            // DEBUG, not INFO: under a flood one log line per 429 would itself load the controller.
            log.debug("DENY 429 {} /{} user={} tenant={} cost={}: rate_limited (retry after {} s)", method, rawPath,
                     id.getUser(), id.getTenantId(), cost, r.getRetryAfterSeconds());
            ctx.abortWith(Response.fromResponse(Errors.response(429, Reasons.RATE_LIMITED,
                    "rate limit exceeded for " + key + " (cost " + cost + ", " + lim.getRateLimitRps()
                            + " tokens/s, burst " + lim.getRateLimitBurst() + ")"))
                    .header("Retry-After", Long.toString(r.getRetryAfterSeconds())).build());
            return false;
        }
        if (capReason != null) {
            reject(ctx, 413, capReason, capMessage, id, method, rawPath);
            return false;
        }
        return true;
    }

    /**
     * Reads the body, but never more than max+1 bytes. Returns null if it is larger
     * than max (Content-Length is checked first, so a declared oversize body is not read).
     */
    private static byte[] boundedBody(ContainerRequestContext ctx, int max) throws IOException {
        if (ctx.getLength() > max) {
            return null;
        }
        InputStream in = ctx.getEntityStream();
        byte[] b = ByteStreams.toByteArray(ByteStreams.limit(in, (long) max + 1));
        return b.length > max ? null : b;
    }

    private void reject(ContainerRequestContext ctx, int status, String reason, String message,
                        CallerIdentity id, String method, String rawPath) {
        log.info("DENY {} {} /{} user={} role={} tenant={}: {}", status, method, rawPath,
                 id.getUser(), id.getGatewayRole().label(), id.getTenantId(), reason);
        ctx.abortWith(Errors.response(status, reason, message));
    }
}
