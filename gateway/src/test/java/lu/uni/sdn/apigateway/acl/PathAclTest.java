package lu.uni.sdn.apigateway.acl;

import com.google.common.base.Splitter;
import lu.uni.sdn.apigateway.tenant.GatewayRole;
import org.junit.Test;

import java.util.List;

import static lu.uni.sdn.apigateway.tenant.GatewayRole.ADMIN;
import static lu.uni.sdn.apigateway.tenant.GatewayRole.NONE;
import static lu.uni.sdn.apigateway.tenant.GatewayRole.OPERATOR;
import static lu.uni.sdn.apigateway.tenant.GatewayRole.SERVICE;
import static lu.uni.sdn.apigateway.tenant.GatewayRole.VIEWER;
import static org.junit.Assert.assertEquals;

/**
 * The B1 role x method x path table, row by row.
 */
public class PathAclTest {

    private static List<String> segs(String p) {
        return p.isEmpty() ? java.util.Collections.emptyList() : Splitter.on('/').splitToList(p);
    }

    private static void expect(String expected, GatewayRole role, String method, String path) {
        PathAcl.Decision d = PathAcl.decide(role, method, segs(path), true);
        String got = d.isAllowed() ? "allow" : d.getReason();
        assertEquals(role + " " + method + " /" + path, expected, got);
    }

    /** Expected outcome for viewer, operator, admin in that order. */
    private static void row(String method, String path, String viewer, String operator, String admin) {
        expect(viewer, VIEWER, method, path);
        expect(operator, OPERATOR, method, path);
        expect(admin, ADMIN, method, path);
    }

    private static final String Y = "allow";
    private static final String N = Reasons.ACL_DENIED;

    @Test
    public void table() {
        row("GET", "whoami", Y, Y, Y);
        row("GET", "flows", Y, Y, Y);
        row("GET", "intents", Y, Y, Y);
        row("POST", "flows", N, Y, Y);
        row("DELETE", "flows", N, Y, Y);
        row("DELETE", "flows/of:0000000000000002/12345", N, Y, Y);
        row("POST", "intents", N, Y, Y);
        row("DELETE", "intents/k1", N, Y, Y);
        row("GET", "v1/devices", Y, Y, Y);
        row("GET", "v1/devices/of:0000000000000002/ports", Y, Y, Y);
        row("GET", "v1/links", Y, Y, Y);
        row("GET", "v1/topology", Y, Y, Y);
        row("GET", "v1/hosts", Y, Y, Y);
        row("GET", "v1/flows", N, N, Y);
        row("GET", "v1/intents", N, N, Y);
        row("GET", "v1/network/configuration", N, N, Y);
        row("POST", "v1/flows", N, N, Y);
        row("PUT", "v1/devices", N, N, Y);
        row("DELETE", "v1/flows/of:0000000000000002/1", N, N, Y);
        row("GET", "admin/tenants", N, N, Y);
        row("POST", "admin/tenants/t1/unblock", N, N, Y);
    }

    @Test
    public void defaultDenyForEveryRole() {
        for (GatewayRole r : new GatewayRole[]{VIEWER, OPERATOR, ADMIN}) {
            expect(N, r, "GET", "");
            expect(N, r, "GET", "application.wadl");
            expect(N, r, "OPTIONS", "whoami");
            expect(N, r, "HEAD", "whoami");
            expect(N, r, "OPTIONS", "flows");
            expect(N, r, "HEAD", "flows");
            expect(N, r, "PATCH", "v1/flows");
            expect(N, r, "PUT", "flows");
            expect(N, r, "GET", "flows/of:0000000000000002");
            expect(N, r, "DELETE", "flows/of:0000000000000002");
            expect(N, r, "DELETE", "intents");
            expect(N, r, "GET", "intents/k1");
            expect(N, r, "GET", "v1");
            expect(N, r, "GET", "admin");
            expect(N, r, "GET", "nosuchpath");
        }
    }

    @Test
    public void unknownAndServiceAccountsOnlyGetWhoami() {
        for (GatewayRole r : new GatewayRole[]{NONE, SERVICE, null}) {
            expect(Y, r, "GET", "whoami");
            expect(Reasons.UNKNOWN_ACCOUNT, r, "GET", "flows");
            expect(Reasons.UNKNOWN_ACCOUNT, r, "GET", "v1/devices");
            expect(Reasons.UNKNOWN_ACCOUNT, r, "GET", "application.wadl");
            expect(Reasons.UNKNOWN_ACCOUNT, r, "OPTIONS", "whoami");
        }
    }

    @Test
    public void aclDisabledIgnoresRoleColumnButNotUnknownAccounts() {
        assertEquals(true, PathAcl.decide(VIEWER, "POST", segs("flows"), false).isAllowed());
        assertEquals(false, PathAcl.decide(VIEWER, "OPTIONS", segs("flows"), false).isAllowed());
        assertEquals(false, PathAcl.decide(NONE, "GET", segs("flows"), false).isAllowed());
        // admin-only surfaces stay admin-only with the toggle off
        for (GatewayRole r : new GatewayRole[]{VIEWER, OPERATOR}) {
            assertEquals(false, PathAcl.decide(r, "GET", segs("v1/configuration"), false).isAllowed());
            assertEquals(false, PathAcl.decide(r, "GET", segs("v1/configuration/lu.uni.sdn.apigateway.GatewayManager"),
                    false).isAllowed());
            assertEquals(false, PathAcl.decide(r, "GET", segs("v1/flows"), false).isAllowed());
            assertEquals(false, PathAcl.decide(r, "POST", segs("v1/flows"), false).isAllowed());
            assertEquals(false, PathAcl.decide(r, "DELETE", segs("v1/flows/of:1/2"), false).isAllowed());
            assertEquals(false, PathAcl.decide(r, "GET", segs("admin/tenants"), false).isAllowed());
            assertEquals(true, PathAcl.decide(r, "GET", segs("v1/hosts"), false).isAllowed());
        }
        assertEquals(true, PathAcl.decide(ADMIN, "GET", segs("v1/configuration"), false).isAllowed());
        assertEquals(false, PathAcl.decide(SERVICE, "GET", segs("v1/devices"), false).isAllowed());
    }
}
