package lu.uni.sdn.apigateway.conflict;

import lu.uni.sdn.apigateway.GatewaySnapshot;
import lu.uni.sdn.apigateway.conflict.ConflictDetector.OwnedRule;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.intent.FlowRuleIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.IntentState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects every tenant's rules, grouped by device: direct flows (tenant ApplicationId,
 * not being removed) plus the flows compiled from its intents (FlowRuleIntent rules of
 * intents not WITHDRAWN/FAILED/PURGE_REQ). Compiled flows carry appId
 * org.onosproject.net.intent, so they are attributed through the tenant's intents.
 */
public final class TenantRules {

    private static final Set<IntentState> GONE =
            EnumSet.of(IntentState.WITHDRAWN, IntentState.FAILED, IntentState.PURGE_REQ);

    private TenantRules() {
    }

    /**
     * Collects all tenants' rules.
     *
     * @param snap    registry + tenant app ids
     * @param flows   flow rule service
     * @param intents intent service
     * @return device to rules on it
     */
    public static Map<DeviceId, List<OwnedRule>> collect(GatewaySnapshot snap, FlowRuleService flows,
                                                         IntentService intents) {
        Map<DeviceId, List<OwnedRule>> out = new HashMap<>();
        for (Map.Entry<String, ApplicationId> e : snap.appIds().entrySet()) {
            String tenant = e.getKey();
            for (FlowEntry f : flows.getFlowEntriesById(e.getValue())) {
                if (f.state() != FlowEntry.FlowEntryState.PENDING_REMOVE
                        && f.state() != FlowEntry.FlowEntryState.REMOVED) {
                    add(out, tenant, f);
                }
            }
            for (Intent i : intents.getIntentsByAppId(e.getValue())) {
                IntentState st = intents.getIntentState(i.key());
                if (st != null && GONE.contains(st)) {
                    continue;
                }
                for (Intent inst : intents.getInstallableIntents(i.key())) {
                    if (inst instanceof FlowRuleIntent) {
                        for (FlowRule r : ((FlowRuleIntent) inst).flowRules()) {
                            add(out, tenant, r);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void add(Map<DeviceId, List<OwnedRule>> out, String tenant, FlowRule r) {
        out.computeIfAbsent(r.deviceId(), k -> new ArrayList<>()).add(new OwnedRule(tenant, r));
    }
}
