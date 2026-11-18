package lu.uni.sdn.apigateway.rest;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

/**
 * A rejection with an HTTP status and a reason code (see acl.Reasons). Rendered
 * as {"code", "reason", "message"} by {@link GatewayExceptionMapper}.
 */
public class GatewayException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String reason;
    private final long retryAfterSeconds;

    /**
     * Creates a rejection.
     *
     * @param status  HTTP status
     * @param reason  reason code
     * @param message human-readable detail
     */
    public GatewayException(Response.Status status, String reason, String message) {
        this(status.getStatusCode(), reason, message, 0);
    }

    /**
     * Creates a rejection with an optional Retry-After.
     *
     * @param status            HTTP status code
     * @param reason            reason code
     * @param message           human-readable detail
     * @param retryAfterSeconds Retry-After header value (0 = no header)
     */
    public GatewayException(int status, String reason, String message, long retryAfterSeconds) {
        super(message, status);
        this.status = status;
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Retry-After seconds, 0 for none.
     *
     * @return seconds
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * HTTP status code.
     *
     * @return status
     */
    public int status() {
        return status;
    }

    /**
     * Reason code.
     *
     * @return reason
     */
    public String reason() {
        return reason;
    }
}
