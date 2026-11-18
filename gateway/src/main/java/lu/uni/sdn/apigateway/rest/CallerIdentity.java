package lu.uni.sdn.apigateway.rest;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import lombok.Value;
import lu.uni.sdn.apigateway.tenant.GatewayRole;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;

import javax.ws.rs.core.SecurityContext;
import java.security.Principal;

/**
 * Who is calling: Karaf user name and roles (from the servlet security context)
 * plus tenant and gateway role (from the tenant registry). ADMIN requires both a
 * listing in tenants.json "admins" and the Karaf "admin" role; otherwise NONE.
 */
@Value
public class CallerIdentity {

    /**
     * Karaf roles probed with {@link SecurityContext#isUserInRole}. The servlet API
     * cannot enumerate a user's roles, so we test the ones that exist in this
     * deployment's users.properties. ("group" is not a role: it is the marker in
     * the {@code _g_\:name = group,role1,...} group syntax.)
     */
    public static final ImmutableList<String> KNOWN_KARAF_ROLES = ImmutableList.of(
            "admin", "viewer", "tenant", "manager", "systembundles", "ssh", "webconsole");

    /** Karaf role a gateway admin must also hold. */
    public static final String KARAF_ADMIN_ROLE = "admin";

    /** Karaf user name, or null if the container gave no principal. */
    String user;
    ImmutableSet<String> roles;
    /** Tenant id, or null if the user is not a tenant account. */
    String tenantId;
    GatewayRole gatewayRole;

    /**
     * Resolves the caller.
     *
     * @param sc       JAX-RS security context of the request
     * @param registry current tenant registry
     * @return identity
     */
    public static CallerIdentity resolve(SecurityContext sc, TenantRegistry registry) {
        Principal p = sc == null ? null : sc.getUserPrincipal();
        String user = p == null ? null : p.getName();
        ImmutableSet.Builder<String> roles = ImmutableSet.builder();
        if (sc != null) {
            KNOWN_KARAF_ROLES.stream().filter(sc::isUserInRole).forEach(roles::add);
        }
        ImmutableSet<String> karafRoles = roles.build();
        String tenantId = registry.tenantOf(user).map(Tenant::getId).orElse(null);
        GatewayRole role = registry.roleOf(user);
        if (role == GatewayRole.ADMIN && !karafRoles.contains(KARAF_ADMIN_ROLE)) {
            // Gateway admin needs both: listed in tenants.json "admins" AND the Karaf admin role.
            role = GatewayRole.NONE;
        }
        return new CallerIdentity(user, karafRoles, tenantId, role);
    }
}
