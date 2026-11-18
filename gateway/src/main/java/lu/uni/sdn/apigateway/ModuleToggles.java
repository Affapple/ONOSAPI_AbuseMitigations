package lu.uni.sdn.apigateway;

import lombok.Value;

/**
 * Snapshot of the module on/off switches (component config). Immutable, so a
 * request sees one consistent set even if an admin runs {@code cfg set} meanwhile.
 */
@Value
public class ModuleToggles {
    boolean aclEnabled;
    boolean rateLimitEnabled;
    boolean quotaEnabled;
    boolean validationEnabled;
    boolean conflictEnabled;
    boolean adaptiveEnabled;
    boolean auditEnabled;
}
