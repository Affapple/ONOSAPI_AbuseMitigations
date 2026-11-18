package lu.uni.sdn.apigateway.tenant;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

/**
 * ONOS network-config wrapper around tenants.json.
 *
 * <p>Pushed as {@code POST /onos/v1/network/configuration/apps/lu.uni.sdn.api_gateway/tenants}
 * with the tenants.json document as body. The JSON is kept verbatim; parsing and
 * validation are done by {@link TenantRegistry#fromJson}.</p>
 */
public class TenantsConfig extends Config<ApplicationId> {

    /** Config key under apps/lu.uni.sdn.api_gateway. */
    public static final String KEY = "tenants";

    @Override
    public boolean isValid() {
        try {
            TenantRegistry.fromJson(node());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Parses the config into a registry.
     *
     * @return registry
     * @throws IllegalArgumentException if the document is invalid
     */
    public TenantRegistry registry() {
        return TenantRegistry.fromJson(node());
    }
}
