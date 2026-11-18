package lu.uni.sdn.apigateway.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * Builds the uniform JSON error body {"code": int, "reason": string, "message": string}.
 */
public final class Errors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Errors() {
    }

    /**
     * Builds an error response.
     *
     * @param status  HTTP status code
     * @param reason  reason code
     * @param message detail
     * @return response with a JSON body
     */
    public static Response response(int status, String reason, String message) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("code", status);
        n.put("reason", reason);
        n.put("message", message);
        // Serialized here so no MessageBodyWriter lookup can fail on the error path.
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(n.toString()).build();
    }
}
