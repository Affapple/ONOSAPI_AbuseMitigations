package lu.uni.sdn.apigateway.throttle;

import lombok.Value;
import lu.uni.sdn.apigateway.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;

/**
 * B6 settings (component config). Immutable snapshot; passed on every call so a
 * {@code cfg set} takes effect on the next request. Fixed mode with effect "rate"
 * acts through the B2 token bucket, so it needs {@code rateLimitEnabled=true}.
 */
@Value
public class ThrottleSettings {
    /** "adaptive" (B6 levels) or "fixed" (Lab2: any violation blocks writes for fixedPenaltySeconds). */
    public static final String ADAPTIVE = "adaptive";
    public static final String FIXED = "fixed";
    /** Fixed-mode effect: the B2 refill rate x fixedRateFactor on all requests (Lab2-like). */
    public static final String EFFECT_RATE = "rate";
    /** Fixed-mode effect: writes 429 for the penalty; reads untouched. */
    public static final String EFFECT_BLOCK_WRITES = "block-writes";

    public static final String THROTTLE_MODE = "throttleMode";
    public static final String THROTTLE_MODE_DEFAULT = "adaptive";
    public static final String SCORE_HALF_LIFE = "scoreHalfLife";
    public static final double SCORE_HALF_LIFE_DEFAULT = 60.0;
    public static final String LEVEL1_THRESHOLD = "level1Threshold";
    public static final double LEVEL1_THRESHOLD_DEFAULT = 10.0;
    public static final String LEVEL2_THRESHOLD = "level2Threshold";
    public static final double LEVEL2_THRESHOLD_DEFAULT = 25.0;
    public static final String LEVEL3_THRESHOLD = "level3Threshold";
    public static final double LEVEL3_THRESHOLD_DEFAULT = 50.0;
    public static final String RESTRICTED_RATE_FACTOR = "restrictedRateFactor";
    public static final double RESTRICTED_RATE_FACTOR_DEFAULT = 0.5;
    public static final String COOLDOWN = "cooldown";
    public static final int COOLDOWN_DEFAULT = 30;
    public static final String COOLDOWN_MAX = "cooldownMax";
    public static final int COOLDOWN_MAX_DEFAULT = 960;
    public static final String FIXED_PENALTY = "fixedPenalty";
    public static final int FIXED_PENALTY_DEFAULT = 60;
    public static final String FIXED_EFFECT = "fixedEffect";
    public static final String FIXED_EFFECT_DEFAULT = "rate";
    public static final String FIXED_RATE_FACTOR = "fixedRateFactor";
    public static final double FIXED_RATE_FACTOR_DEFAULT = 0.1;
    public static final String WEIGHT_RATE_LIMIT = "weightRateLimit";
    public static final double WEIGHT_RATE_LIMIT_DEFAULT = 1.0;
    public static final String WEIGHT_QUOTA = "weightQuota";
    public static final double WEIGHT_QUOTA_DEFAULT = 2.0;
    public static final String WEIGHT_VALIDATION = "weightValidation";
    public static final double WEIGHT_VALIDATION_DEFAULT = 3.0;
    public static final String WEIGHT_CONFLICT = "weightConflict";
    public static final double WEIGHT_CONFLICT_DEFAULT = 5.0;
    public static final String WEIGHT_ACL = "weightAcl";
    public static final double WEIGHT_ACL_DEFAULT = 3.0;

    private static final Logger LOG = LoggerFactory.getLogger(ThrottleSettings.class);

    String mode;
    double scoreHalfLifeSeconds;
    double level1Threshold;
    double level2Threshold;
    double level3Threshold;
    /** Token refill-rate factor while at level 1 or higher (not blocked). */
    double restrictedRateFactor;
    int cooldownSeconds;
    /** Cap of the doubling cooldown. */
    int cooldownMaxSeconds;
    int fixedPenaltySeconds;
    /** {@link #EFFECT_RATE} or {@link #EFFECT_BLOCK_WRITES}. */
    String fixedEffect;
    /** Refill-rate factor during a fixed penalty with effect "rate". */
    double fixedRateFactor;
    double weightRateLimit;
    double weightQuota;
    double weightValidation;
    double weightConflict;
    double weightAcl;

    /**
     * Parses the B6 settings. Thresholds must increase (level1 &lt; level2 &lt; level3),
     * otherwise all three fall back to the defaults with a WARN.
     *
     * @param p component properties (null: defaults)
     * @return settings
     */
    public static ThrottleSettings from(Dictionary<?, ?> p) {
        double l1 = Props.positiveDouble(p, LEVEL1_THRESHOLD, LEVEL1_THRESHOLD_DEFAULT);
        double l2 = Props.positiveDouble(p, LEVEL2_THRESHOLD, LEVEL2_THRESHOLD_DEFAULT);
        double l3 = Props.positiveDouble(p, LEVEL3_THRESHOLD, LEVEL3_THRESHOLD_DEFAULT);
        if (!(l1 < l2 && l2 < l3)) {
            LOG.warn("Throttle thresholds must increase ({} < {} < {}); using defaults", l1, l2, l3);
            l1 = LEVEL1_THRESHOLD_DEFAULT;
            l2 = LEVEL2_THRESHOLD_DEFAULT;
            l3 = LEVEL3_THRESHOLD_DEFAULT;
        }
        int cooldown = Props.positiveInt(p, COOLDOWN, COOLDOWN_DEFAULT);
        return new ThrottleSettings(Props.choice(p, THROTTLE_MODE, THROTTLE_MODE_DEFAULT, ADAPTIVE, FIXED),
                Props.positiveDouble(p, SCORE_HALF_LIFE, SCORE_HALF_LIFE_DEFAULT), l1, l2, l3,
                Props.positiveDouble(p, RESTRICTED_RATE_FACTOR, RESTRICTED_RATE_FACTOR_DEFAULT), cooldown,
                Math.max(cooldown, Props.positiveInt(p, COOLDOWN_MAX, COOLDOWN_MAX_DEFAULT)),
                Props.positiveInt(p, FIXED_PENALTY, FIXED_PENALTY_DEFAULT),
                Props.choice(p, FIXED_EFFECT, FIXED_EFFECT_DEFAULT, EFFECT_RATE, EFFECT_BLOCK_WRITES),
                Props.positiveDouble(p, FIXED_RATE_FACTOR, FIXED_RATE_FACTOR_DEFAULT),
                Props.nonNegativeDouble(p, WEIGHT_RATE_LIMIT, WEIGHT_RATE_LIMIT_DEFAULT),
                Props.nonNegativeDouble(p, WEIGHT_QUOTA, WEIGHT_QUOTA_DEFAULT),
                Props.nonNegativeDouble(p, WEIGHT_VALIDATION, WEIGHT_VALIDATION_DEFAULT),
                Props.nonNegativeDouble(p, WEIGHT_CONFLICT, WEIGHT_CONFLICT_DEFAULT),
                Props.nonNegativeDouble(p, WEIGHT_ACL, WEIGHT_ACL_DEFAULT));
    }

    /**
     * Whether the fixed (Lab2) policy is selected.
     *
     * @return true for fixed mode
     */
    public boolean isFixed() {
        return FIXED.equals(mode);
    }

    /**
     * Whether fixed mode blocks writes (instead of slowing all requests).
     *
     * @return true for block-writes
     */
    public boolean isFixedBlockWrites() {
        return EFFECT_BLOCK_WRITES.equals(fixedEffect);
    }

    /**
     * Enter threshold of a level (1..3).
     *
     * @param level level
     * @return threshold
     */
    public double threshold(int level) {
        switch (level) {
            case 1:
                return level1Threshold;
            case 2:
                return level2Threshold;
            case 3:
                return level3Threshold;
            default:
                return 0;
        }
    }
}
