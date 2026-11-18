package lu.uni.sdn.apigateway.acl;

import com.google.common.collect.ImmutableSet;
import lombok.Value;
import lu.uni.sdn.apigateway.tenant.GatewayRole;

import java.util.List;
import java.util.Set;

import static lu.uni.sdn.apigateway.tenant.GatewayRole.ADMIN;
import static lu.uni.sdn.apigateway.tenant.GatewayRole.OPERATOR;
import static lu.uni.sdn.apigateway.tenant.GatewayRole.VIEWER;

/**
 * Layer 1 role x method x path table (plan B1). Default deny: a request is
 * allowed only if its exact (method, path shape) is listed below for the role.
 *
 * <pre>
 *   GET    whoami                       any authenticated caller (incl. NONE, SERVICE)
 *   GET    flows | intents              viewer, operator, admin
 *   POST   flows | intents              operator, admin
 *   DELETE flows                        operator, admin      (batch body)
 *   DELETE flows/{deviceId}/{flowId}    operator, admin
 *   DELETE intents/{key}                operator, admin
 *   GET    v1/{devices|links|topology|hosts}[/...]   viewer, operator, admin
 *   GET    v1/{anything else}           admin
 *   POST|PUT|DELETE v1/...              admin
 *   GET|POST|PUT|DELETE admin/...       admin
 * </pre>
 * Everything else (other paths, HEAD, OPTIONS, PATCH, ...) is denied for every
 * role. NONE and SERVICE callers only get whoami. With {@code aclEnabled=false}
 * viewers may also write flows/intents (the role column of the tenant rows is
 * ignored), but admin-only rows (non-allowlisted v1 reads, v1 writes, admin/*)
 * stay admin-only and unknown accounts are still denied.
 */
public final class PathAcl {

    /** First segments under v1/ that tenants (viewer, operator) may read. */
    public static final Set<String> TENANT_READABLE_V1 =
            ImmutableSet.of("devices", "links", "topology", "hosts");

    private static final Set<GatewayRole> READERS = ImmutableSet.of(VIEWER, OPERATOR, ADMIN);
    private static final Set<GatewayRole> WRITERS = ImmutableSet.of(OPERATOR, ADMIN);
    private static final Set<GatewayRole> ADMINS = ImmutableSet.of(ADMIN);
    private static final Set<GatewayRole> NOBODY = ImmutableSet.of();

    private PathAcl() {
    }

    /** Result of an ACL decision. */
    @Value
    public static class Decision {
        boolean allowed;
        /** Reason code when denied, null when allowed. */
        String reason;
        String message;

        static Decision allow() {
            return new Decision(true, null, null);
        }

        static Decision deny(String reason, String message) {
            return new Decision(false, reason, message);
        }
    }

    /**
     * Decides whether a request may proceed.
     *
     * @param role       caller's gateway role
     * @param method     HTTP method
     * @param segments   decoded path segments relative to /onos/gw, already sanitized
     * @param aclEnabled component-config toggle
     * @return decision
     */
    public static Decision decide(GatewayRole role, String method, List<String> segments, boolean aclEnabled) {
        if (segments.size() == 1 && segments.get(0).equals("whoami") && method.equals("GET")) {
            return Decision.allow();
        }
        if (role == null || role == GatewayRole.NONE || role == GatewayRole.SERVICE) {
            return Decision.deny(Reasons.UNKNOWN_ACCOUNT,
                    "account has no gateway role (not in tenants.json, or the service account)");
        }
        Set<GatewayRole> allowed = allowedRoles(method, segments);
        if (allowed.isEmpty()) {
            return Decision.deny(Reasons.ACL_DENIED, method + " /" + String.join("/", segments)
                    + " is not an allowed gateway operation");
        }
        if (allowed.contains(role)) {
            return Decision.allow();
        }
        // aclEnabled=false only relaxes the tenant rows (flows/intents/allowlisted v1 reads);
        // admin-only surfaces (other v1 reads, v1 writes, admin/*) stay admin-only.
        if (!aclEnabled && allowed != ADMINS) {
            return Decision.allow();
        }
        return Decision.deny(Reasons.ACL_DENIED, "role " + role.label() + " may not " + method + " /"
                + String.join("/", segments));
    }

    private static Set<GatewayRole> allowedRoles(String method, List<String> s) {
        if (s.isEmpty()) {
            return NOBODY;
        }
        String head = s.get(0);
        int n = s.size();
        switch (head) {
            case "flows":
                if (n == 1 && method.equals("GET")) {
                    return READERS;
                }
                if (n == 1 && (method.equals("POST") || method.equals("DELETE"))) {
                    return WRITERS;
                }
                if (n == 3 && method.equals("DELETE")) {
                    return WRITERS;
                }
                return NOBODY;
            case "intents":
                if (n == 1 && method.equals("GET")) {
                    return READERS;
                }
                if (n == 1 && method.equals("POST")) {
                    return WRITERS;
                }
                if (n == 2 && method.equals("DELETE")) {
                    return WRITERS;
                }
                return NOBODY;
            case "v1":
                if (n < 2) {
                    return NOBODY;
                }
                if (method.equals("GET")) {
                    return TENANT_READABLE_V1.contains(s.get(1)) ? READERS : ADMINS;
                }
                if (method.equals("POST") || method.equals("PUT") || method.equals("DELETE")) {
                    return ADMINS;
                }
                return NOBODY;
            case "admin":
                if (n >= 2 && (method.equals("GET") || method.equals("POST")
                        || method.equals("PUT") || method.equals("DELETE"))) {
                    return ADMINS;
                }
                return NOBODY;
            default:
                return NOBODY;
        }
    }
}
