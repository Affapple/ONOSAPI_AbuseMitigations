package lu.uni.sdn.apigateway;

import com.google.common.collect.ImmutableMap;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import org.onosproject.core.ApplicationId;

import java.util.Map;
import java.util.Optional;

/**
 * Immutable pairing of a tenant registry with the ApplicationIds registered for
 * its tenants. GatewayManager publishes a new instance with a single volatile
 * write; GatewayFilter captures one per request so every layer of that request
 * sees the same registry and app ids.
 */
public final class GatewaySnapshot {

    private static final GatewaySnapshot EMPTY =
            new GatewaySnapshot(TenantRegistry.empty(), ImmutableMap.of());

    private final TenantRegistry registry;
    private final ImmutableMap<String, ApplicationId> appIdByTenant;
    private final ImmutableMap<ApplicationId, String> tenantByAppId;

    /**
     * Creates a snapshot.
     *
     * @param registry      tenant registry
     * @param appIdByTenant tenant id to ApplicationId; must cover every tenant of the registry
     */
    public GatewaySnapshot(TenantRegistry registry, Map<String, ApplicationId> appIdByTenant) {
        for (String t : registry.tenants().keySet()) {
            if (!appIdByTenant.containsKey(t)) {
                throw new IllegalArgumentException("no ApplicationId for tenant " + t);
            }
        }
        this.registry = registry;
        this.appIdByTenant = ImmutableMap.copyOf(appIdByTenant);
        ImmutableMap.Builder<ApplicationId, String> rev = ImmutableMap.builder();
        appIdByTenant.forEach((t, a) -> rev.put(a, t));
        this.tenantByAppId = rev.build();
    }

    /**
     * Snapshot with an empty registry (nobody is known to the gateway).
     *
     * @return empty snapshot
     */
    public static GatewaySnapshot empty() {
        return EMPTY;
    }

    /**
     * Tenant registry.
     *
     * @return registry
     */
    public TenantRegistry registry() {
        return registry;
    }

    /**
     * ApplicationIds of all tenants, keyed by tenant id.
     *
     * @return map
     */
    public ImmutableMap<String, ApplicationId> appIds() {
        return appIdByTenant;
    }

    /**
     * ApplicationId of a tenant.
     *
     * @param tenantId tenant id (may be null)
     * @return app id, if the tenant is known
     */
    public Optional<ApplicationId> appId(String tenantId) {
        return Optional.ofNullable(tenantId == null ? null : appIdByTenant.get(tenantId));
    }

    /**
     * Tenant that owns an ApplicationId.
     *
     * @param appId application id
     * @return tenant, if the app id is a tenant app id
     */
    public Optional<Tenant> tenantOfApp(ApplicationId appId) {
        String t = appId == null ? null : tenantByAppId.get(appId);
        return t == null ? Optional.empty() : registry.tenant(t);
    }
}
