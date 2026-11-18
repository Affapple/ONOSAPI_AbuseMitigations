package lu.uni.sdn.apigateway.acl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Overwrites the client-supplied "appId" of flow/intent JSON with the tenant's
 * ApplicationId name before ONOS's codecs decode it.
 *
 * <p>Why this works (O3/O4, from onos-core-common 2.7.0 sources):
 * {@code FlowRuleCodec.decode} uses {@code coreService.registerApplication(json.appId)}
 * (default "org.onosproject.rest" when absent), and {@code IntentCodec.intentAttributes}
 * uses {@code coreService.getAppId(json.appId)} for both the intent's appId and its key.
 * So whatever "appId" says when decode runs is what the rule/intent carries. Callers
 * must still verify the decoded appId afterwards (defense in depth).</p>
 */
public final class AppIdImposer {

    /** JSON member name used by both codecs. */
    public static final String APP_ID = "appId";

    private AppIdImposer() {
    }

    /**
     * Validates the {"flows": [ {...}, ... ]} envelope (same shape as
     * POST /onos/v1/flows) and sets every flow's appId.
     *
     * @param root    request body
     * @param appName tenant ApplicationId name
     * @return the flows array (modified in place)
     * @throws IllegalArgumentException if the envelope is malformed or empty
     */
    public static ArrayNode imposeOnFlows(JsonNode root, String appName) {
        if (root == null || !root.isObject() || !root.path("flows").isArray()) {
            throw new IllegalArgumentException("body must be {\"flows\": [ ... ]}");
        }
        ArrayNode flows = (ArrayNode) root.get("flows");
        if (flows.size() == 0) {
            throw new IllegalArgumentException("\"flows\" must not be empty");
        }
        for (JsonNode f : flows) {
            if (!f.isObject()) {
                throw new IllegalArgumentException("every element of \"flows\" must be an object");
            }
            ((ObjectNode) f).put(APP_ID, appName);
        }
        return flows;
    }

    /**
     * Validates a single intent object (same shape as POST /onos/v1/intents)
     * and sets its appId.
     *
     * @param root    request body
     * @param appName tenant ApplicationId name
     * @return the intent object (modified in place)
     * @throws IllegalArgumentException if the body is not an object
     */
    public static ObjectNode imposeOnIntent(JsonNode root, String appName) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("body must be one intent object");
        }
        ObjectNode o = (ObjectNode) root;
        o.put(APP_ID, appName);
        return o;
    }
}
