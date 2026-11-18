package lu.uni.sdn.apigateway.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import lu.uni.sdn.apigateway.tenant.GatewayRole;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.core.SecurityContext;
import java.io.InputStream;
import java.security.Principal;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests for {@link CallerIdentity#resolve}.
 */
public class CallerIdentityTest {

    private TenantRegistry registry;

    @Before
    public void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/tenants.json")) {
            registry = TenantRegistry.fromJson(new ObjectMapper().readTree(in));
        }
    }

    @Test
    public void tenantOperator() {
        CallerIdentity id = CallerIdentity.resolve(sc("t1-op", "group", "tenant"), registry);
        assertEquals("t1-op", id.getUser());
        assertEquals("t1", id.getTenantId());
        assertEquals(GatewayRole.OPERATOR, id.getGatewayRole());
        assertEquals(ImmutableSet.of("tenant"), id.getRoles());
    }

    @Test
    public void serviceAccountHasNoTenant() {
        CallerIdentity id = CallerIdentity.resolve(sc("gw-svc", "group", "viewer"), registry);
        assertNull(id.getTenantId());
        assertEquals(GatewayRole.SERVICE, id.getGatewayRole());
    }

    @Test
    public void adminFromRegistryNotFromKarafRole() {
        assertEquals(GatewayRole.ADMIN,
                CallerIdentity.resolve(sc("onos", "admin", "viewer"), registry).getGatewayRole());
        // Karaf admin role alone does not make someone a gateway admin.
        assertEquals(GatewayRole.NONE,
                CallerIdentity.resolve(sc("karaf", "admin", "viewer"), registry).getGatewayRole());
        // Listed in "admins" without the Karaf admin role is not enough either.
        assertEquals(GatewayRole.NONE,
                CallerIdentity.resolve(sc("onos", "viewer"), registry).getGatewayRole());
    }

    @Test
    public void noPrincipal() {
        CallerIdentity id = CallerIdentity.resolve(sc(null), registry);
        assertNull(id.getUser());
        assertNull(id.getTenantId());
        assertEquals(GatewayRole.NONE, id.getGatewayRole());
    }

    private static SecurityContext sc(String user, String... roles) {
        Set<String> r = ImmutableSet.copyOf(roles);
        return new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return user == null ? null : () -> user;
            }

            @Override
            public boolean isUserInRole(String role) {
                return r.contains(role);
            }

            @Override
            public boolean isSecure() {
                return false;
            }

            @Override
            public String getAuthenticationScheme() {
                return BASIC_AUTH;
            }
        };
    }
}
