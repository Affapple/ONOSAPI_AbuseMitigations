package lu.uni.sdn.apigateway.rest;

import lombok.Value;
import lu.uni.sdn.apigateway.GatewaySnapshot;
import lu.uni.sdn.apigateway.ModuleToggles;
import lu.uni.sdn.apigateway.ProxySettings;
import lu.uni.sdn.apigateway.quota.QuotaLimits;
import lu.uni.sdn.apigateway.validation.ValidationLimits;

import java.util.List;

/**
 * Everything layer 1 decided about a request, captured once by GatewayFilter and
 * stored as request property {@link GatewayFilter#REQUEST_PROPERTY}. Resources use
 * this object, never the live service state, so one request sees one consistent
 * registry, app-id map and set of toggles.
 */
@Value
public class GatewayRequest {
    CallerIdentity identity;
    GatewaySnapshot snapshot;
    ModuleToggles toggles;
    ProxySettings proxy;
    /** Default quota limits at request time (per-tenant overrides applied by the resource). */
    QuotaLimits quotaLimits;
    /** Payload validation limits at request time. */
    ValidationLimits validationLimits;
    /** Decoded, sanitized path segments relative to /onos/gw. */
    List<String> segments;
    /**
     * Number of flows the rate limiter counted and charged for a POST/DELETE /flows
     * batch (-1 = body not countable); null when the limiter did not count (module
     * off, admin, or not a flow batch). FlowsResource checks the decoded count
     * against it.
     */
    Integer chargedFlows;
}
