package lu.uni.sdn.apigateway.tenant;

/**
 * Role of a caller inside the gateway. Karaf only knows the login role
 * ("tenant", "viewer", "admin"); the gateway role comes from tenants.json.
 */
public enum GatewayRole {
    /** Listed in "admins". */
    ADMIN("admin"),
    /** Tenant account with role "operator". */
    OPERATOR("operator"),
    /** Tenant account with role "viewer". */
    VIEWER("viewer"),
    /** The read-forwarding service account ("serviceAccount"). */
    SERVICE("service"),
    /** Authenticated by Karaf but unknown to the gateway. */
    NONE("none");

    private final String label;

    GatewayRole(String label) {
        this.label = label;
    }

    /**
     * Returns the lower-case label used in JSON.
     *
     * @return label
     */
    public String label() {
        return label;
    }

    /**
     * Parses a tenant account role from tenants.json ("operator" or "viewer").
     *
     * @param s role string
     * @return role
     * @throws IllegalArgumentException if it is not a tenant account role
     */
    public static GatewayRole parseAccountRole(String s) {
        if (OPERATOR.label.equals(s)) {
            return OPERATOR;
        }
        if (VIEWER.label.equals(s)) {
            return VIEWER;
        }
        throw new IllegalArgumentException("account role must be \"operator\" or \"viewer\", got \"" + s + "\"");
    }
}
