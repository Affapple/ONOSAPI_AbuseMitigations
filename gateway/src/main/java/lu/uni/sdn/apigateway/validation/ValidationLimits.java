package lu.uni.sdn.apigateway.validation;

import lombok.Value;
import lu.uni.sdn.apigateway.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;

/**
 * Numeric limits of payload validation (B4), from component config.
 */
@Value
public class ValidationLimits {
    public static final String MIN_PRIORITY = "minPriority";
    public static final int MIN_PRIORITY_DEFAULT = 100;
    public static final String MAX_PRIORITY = "maxPriority";
    public static final int MAX_PRIORITY_DEFAULT = 30000;
    public static final String MAX_TIMEOUT = "maxTimeout";
    public static final int MAX_TIMEOUT_DEFAULT = 300;

    private static final Logger LOG = LoggerFactory.getLogger(ValidationLimits.class);

    /** Lowest priority a tenant flow/intent may use. */
    int minPriority;
    /** Highest priority a tenant flow/intent may use (above it: core/controller rules). */
    int maxPriority;
    /** Longest idle/hard timeout in seconds; permanent rules are never allowed. */
    int maxTimeout;

    /**
     * Parses the B4 limits; minPriority &gt; maxPriority falls back to the defaults.
     *
     * @param p component properties (null: defaults)
     * @return limits
     */
    public static ValidationLimits from(Dictionary<?, ?> p) {
        int min = Props.positiveInt(p, MIN_PRIORITY, MIN_PRIORITY_DEFAULT);
        int max = Props.positiveInt(p, MAX_PRIORITY, MAX_PRIORITY_DEFAULT);
        if (min > max) {
            LOG.warn("minPriority {} > maxPriority {}; using defaults", min, max);
            min = MIN_PRIORITY_DEFAULT;
            max = MAX_PRIORITY_DEFAULT;
        }
        return new ValidationLimits(min, max, Props.positiveInt(p, MAX_TIMEOUT, MAX_TIMEOUT_DEFAULT));
    }
}
