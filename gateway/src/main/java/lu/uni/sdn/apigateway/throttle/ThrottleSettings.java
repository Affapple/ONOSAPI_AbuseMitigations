package lu.uni.sdn.apigateway.throttle;

import lombok.Value;

/**
 * B6 settings (component config). Immutable snapshot; passed on every call so a
 * {@code cfg set} takes effect on the next request.
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
