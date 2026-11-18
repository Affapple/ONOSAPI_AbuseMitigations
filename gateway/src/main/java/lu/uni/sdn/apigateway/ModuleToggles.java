package lu.uni.sdn.apigateway;

import lombok.Value;

import java.util.Dictionary;

/**
 * Snapshot of the module on/off switches (component config). Immutable, so a
 * request sees one consistent set even if an admin runs {@code cfg set} meanwhile.
 * All modules default to enabled (fail secure); the evaluation turns modules off
 * explicitly for config C2 ("ACL only").
 */
@Value
public class ModuleToggles {
    public static final String ACL_ENABLED = "aclEnabled";
    public static final boolean ACL_ENABLED_DEFAULT = true;
    public static final String RATE_LIMIT_ENABLED = "rateLimitEnabled";
    public static final boolean RATE_LIMIT_ENABLED_DEFAULT = true;
    public static final String QUOTA_ENABLED = "quotaEnabled";
    public static final boolean QUOTA_ENABLED_DEFAULT = true;
    public static final String VALIDATION_ENABLED = "validationEnabled";
    public static final boolean VALIDATION_ENABLED_DEFAULT = true;
    public static final String CONFLICT_ENABLED = "conflictEnabled";
    public static final boolean CONFLICT_ENABLED_DEFAULT = true;
    public static final String ADAPTIVE_ENABLED = "adaptiveEnabled";
    public static final boolean ADAPTIVE_ENABLED_DEFAULT = true;

    boolean aclEnabled;
    boolean rateLimitEnabled;
    boolean quotaEnabled;
    boolean validationEnabled;
    boolean conflictEnabled;
    boolean adaptiveEnabled;

    /**
     * Parses the toggles.
     *
     * @param p component properties (null: defaults)
     * @return toggles
     */
    public static ModuleToggles from(Dictionary<?, ?> p) {
        return new ModuleToggles(Props.bool(p, ACL_ENABLED, ACL_ENABLED_DEFAULT),
                Props.bool(p, RATE_LIMIT_ENABLED, RATE_LIMIT_ENABLED_DEFAULT),
                Props.bool(p, QUOTA_ENABLED, QUOTA_ENABLED_DEFAULT),
                Props.bool(p, VALIDATION_ENABLED, VALIDATION_ENABLED_DEFAULT),
                Props.bool(p, CONFLICT_ENABLED, CONFLICT_ENABLED_DEFAULT),
                Props.bool(p, ADAPTIVE_ENABLED, ADAPTIVE_ENABLED_DEFAULT));
    }
}
