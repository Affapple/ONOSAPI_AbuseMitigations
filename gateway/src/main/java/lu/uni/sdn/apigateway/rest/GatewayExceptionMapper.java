package lu.uni.sdn.apigateway.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import lu.uni.sdn.apigateway.acl.Reasons;
import org.onlab.util.ItemNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;

/**
 * Maps every exception that escapes a resource or filter to a JSON error body
 * with a reason code, so no request ever gets Jersey's default HTML/empty error.
 * (Errors raised by Jetty before Jersey runs, i.e. 401 from the Karaf login,
 * are outside Jersey and remain Jetty's HTML page.)
 */
public class GatewayExceptionMapper implements ExceptionMapper<Throwable> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public Response toResponse(Throwable e) {
        if (e instanceof GatewayException) {
            GatewayException g = (GatewayException) e;
            Response r = Errors.response(g.status(), g.reason(), g.getMessage());
            return g.retryAfterSeconds() > 0
                    ? Response.fromResponse(r).header("Retry-After", Long.toString(g.retryAfterSeconds())).build()
                    : r;
        }
        if (e instanceof WebApplicationException) {
            int code = ((WebApplicationException) e).getResponse().getStatus();
            return Errors.response(code, reasonFor(code), e.getMessage());
        }
        if (e instanceof IllegalArgumentException || e instanceof JsonProcessingException
                || e instanceof ClassCastException
                || (e instanceof NullPointerException && isFromDecoding(e))) {
            return Errors.response(400, Reasons.BAD_REQUEST, String.valueOf(e.getMessage()));
        }
        if (e instanceof ItemNotFoundException) {
            return Errors.response(404, Reasons.NOT_FOUND, e.getMessage());
        }
        log.error("Unhandled exception in gateway", e);
        return Errors.response(500, Reasons.INTERNAL_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private static boolean isFromDecoding(Throwable e) {
        for (StackTraceElement s : e.getStackTrace()) {
            if (s.getClassName().startsWith("org.onosproject.codec")) {
                return true;
            }
        }
        return false;
    }

    private static String reasonFor(int code) {
        switch (code) {
            case 400:
                return Reasons.BAD_REQUEST;
            case 404:
                return Reasons.NOT_FOUND;
            case 405:
                return Reasons.METHOD_NOT_ALLOWED;
            case 500:
                return Reasons.INTERNAL_ERROR;
            default:
                return "http_" + code;
        }
    }
}
