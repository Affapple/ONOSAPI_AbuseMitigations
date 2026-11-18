package lu.uni.sdn.apigateway.ratelimit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;

/**
 * Counts the elements of the top-level "flows" array of a request body with a
 * streaming parser (no tree is built; nested objects are skipped), so the batch
 * size cap and the weighted rate-limit cost are known before the body is decoded.
 * Duplicate object keys (at any level) make the body uncountable (-1); the
 * resources parse with the same strictness and reject such bodies with 400.
 */
public final class BatchCounter {

    /** Duplicate keys anywhere are a parse error (so {"flows":[1],"flows":[6]} cannot be miscounted). */
    private static final JsonFactory FACTORY =
            new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private BatchCounter() {
    }

    /**
     * Counts top-level "flows" elements.
     *
     * @param body raw body
     * @return number of elements, or -1 if the body is not an object with exactly one
     *         "flows" array (or has duplicate keys / is malformed)
     */
    public static int countFlows(byte[] body) {
        try (JsonParser p = FACTORY.createParser(body)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return -1;
            }
            int n = -1;
            JsonToken t;
            while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
                String name = p.getCurrentName();
                JsonToken v = p.nextToken();
                if ("flows".equals(name)) {
                    if (v != JsonToken.START_ARRAY) {
                        return -1;
                    }
                    n = 0;
                    while (p.nextToken() != JsonToken.END_ARRAY) {
                        p.skipChildren();
                        n++;
                    }
                } else {
                    p.skipChildren();
                }
            }
            // Read the WHOLE document: a second "flows" (or any duplicate key) later in
            // the body must hit STRICT_DUPLICATE_DETECTION, and nothing may follow the root.
            if (t != JsonToken.END_OBJECT || p.nextToken() != null) {
                return -1;
            }
            return n;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }
}
