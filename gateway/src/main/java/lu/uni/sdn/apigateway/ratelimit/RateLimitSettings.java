package lu.uni.sdn.apigateway.ratelimit;

import lombok.Value;
import lu.uni.sdn.apigateway.Props;

import java.util.Dictionary;

/**
 * Numeric limits of the rate-limit module (B2), from component config.
 */
@Value
public class RateLimitSettings {
    public static final String RATE_LIMIT_RPS = "rateLimitRps";
    public static final double RATE_LIMIT_RPS_DEFAULT = 10.0;
    public static final String RATE_LIMIT_BURST = "rateLimitBurst";
    public static final int RATE_LIMIT_BURST_DEFAULT = 20;
    public static final String MAX_BATCH_SIZE = "maxBatchSize";
    public static final int MAX_BATCH_SIZE_DEFAULT = 50;
    public static final String MAX_BODY_BYTES = "maxBodyBytes";
    public static final int MAX_BODY_BYTES_DEFAULT = 65536;

    /** Token refill rate per tenant, tokens per second. */
    double rateLimitRps;
    /** Bucket capacity per tenant. */
    int rateLimitBurst;
    /** Max flows in one POST/DELETE /gw/flows batch. */
    int maxBatchSize;
    /** Max request body size in bytes. */
    int maxBodyBytes;

    /**
     * Parses the B2 settings.
     *
     * @param p component properties (null: defaults)
     * @return settings
     */
    public static RateLimitSettings from(Dictionary<?, ?> p) {
        return new RateLimitSettings(Props.positiveDouble(p, RATE_LIMIT_RPS, RATE_LIMIT_RPS_DEFAULT),
                Props.positiveInt(p, RATE_LIMIT_BURST, RATE_LIMIT_BURST_DEFAULT),
                Props.positiveInt(p, MAX_BATCH_SIZE, MAX_BATCH_SIZE_DEFAULT),
                Props.positiveInt(p, MAX_BODY_BYTES, MAX_BODY_BYTES_DEFAULT));
    }
}
