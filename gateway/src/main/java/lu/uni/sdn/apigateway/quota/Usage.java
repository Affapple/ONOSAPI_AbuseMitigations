package lu.uni.sdn.apigateway.quota;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import lombok.Value;
import org.onosproject.net.DeviceId;
import org.onosproject.net.intent.Key;

import java.util.Map;

/**
 * A tenant's resource usage as counted for quotas.
 */
@Value
public class Usage {
    /** Ids of the tenant's own (appId) flows, including writes not yet visible in the store. */
    ImmutableSet<Long> directFlowIds;
    /**
     * Flows of the tenant's counted intents: real compiled rules (appId
     * org.onosproject.net.intent) or, for intents not compiled yet, the admission estimate.
     */
    int intentFlows;
    /** Part of intentFlows that is still an estimate (intent not compiled yet). */
    int estimatedIntentFlows;
    /** direct + intent-compiled flows per device. */
    ImmutableMap<DeviceId, Integer> perDevice;
    /** Keys of the tenant's counted intents (not WITHDRAWN/FAILED/PURGE_REQ). */
    ImmutableSet<Key> intentKeys;
    /** Flows per device contributed by each counted intent (real or estimated). */
    ImmutableMap<Key, Map<DeviceId, Integer>> intentContributions;

    /**
     * Total flows (direct + intent-compiled).
     *
     * @return count
     */
    public int totalFlows() {
        return directFlowIds.size() + intentFlows;
    }

    /**
     * Flows on one device.
     *
     * @param d device
     * @return count
     */
    public int onDevice(DeviceId d) {
        return perDevice.getOrDefault(d, 0);
    }
}
