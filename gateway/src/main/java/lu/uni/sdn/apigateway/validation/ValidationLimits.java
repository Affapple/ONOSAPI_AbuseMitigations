package lu.uni.sdn.apigateway.validation;

import lombok.Value;

/**
 * Numeric limits of payload validation (B4), from component config.
 */
@Value
public class ValidationLimits {
    /** Lowest priority a tenant flow/intent may use. */
    int minPriority;
    /** Highest priority a tenant flow/intent may use (above it: core/controller rules). */
    int maxPriority;
    /** Longest idle/hard timeout in seconds; permanent rules are never allowed. */
    int maxTimeout;
}
