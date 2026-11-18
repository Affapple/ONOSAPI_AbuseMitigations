package lu.uni.sdn.apigateway;

import lombok.Value;

/**
 * Numeric limits of the rate-limit module (B2), from component config. Immutable
 * snapshot, replaced on every {@code cfg set}.
 */
@Value
public class Limits {
    /** Token refill rate per tenant, tokens per second. */
    double rateLimitRps;
    /** Bucket capacity per tenant. */
    int rateLimitBurst;
    /** Max flows in one POST/DELETE /gw/flows batch. */
    int maxBatchSize;
    /** Max request body size in bytes. */
    int maxBodyBytes;
}
