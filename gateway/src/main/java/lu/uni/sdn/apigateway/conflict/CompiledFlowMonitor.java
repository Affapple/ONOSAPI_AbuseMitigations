package lu.uni.sdn.apigateway.conflict;

import lu.uni.sdn.apigateway.GatewaySnapshot;
import lu.uni.sdn.apigateway.acl.Ownership.Violation;
import lu.uni.sdn.apigateway.tenant.Tenant;
import org.onlab.util.Tools;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.intent.FlowRuleIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentEvent;
import org.onosproject.net.intent.IntentListener;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.IntentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * B5 compiled-flow check (detective, defence in depth). When a tenant's intent is
 * INSTALLED, its compiled flows are compared with the other tenants' rules on the same
 * devices (destination capture + {@link ConflictDetector#checkOverlap}). On a conflict
 * the intent is withdrawn (only if the stored intent is still the one checked: a
 * resubmission under the same key gets a new IntentId) and a compiled_conflict
 * rejection is reported. One worker, at most {@link #QUEUE} pending checks; on
 * overflow a check is dropped with a WARN (the next INSTALLED event or a rescan covers it).
 */
public final class CompiledFlowMonitor implements IntentListener {

    /** Bound of the check queue. */
    public static final int QUEUE = 1000;

    /** Receives compiled-flow conflicts (counted and scored like any rejection). */
    @FunctionalInterface
    public interface Sink {
        /**
         * Reports a rejection.
         *
         * @param tenantId tenant
         * @param reason   reason code
         * @param message  detail
         */
        void reject(String tenantId, String reason, String message);
    }

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final IntentService intents;
    private final FlowRuleService flows;
    private final Supplier<GatewaySnapshot> snapshot;
    private final BooleanSupplier enabled;
    private final Sink sink;
    private ExecutorService executor;

    /**
     * Creates the monitor (not started).
     *
     * @param intents  intent service
     * @param flows    flow rule service
     * @param snapshot current registry + tenant app ids
     * @param enabled  conflictEnabled toggle
     * @param sink     where conflicts are reported
     */
    public CompiledFlowMonitor(IntentService intents, FlowRuleService flows, Supplier<GatewaySnapshot> snapshot,
                               BooleanSupplier enabled, Sink sink) {
        this.intents = intents;
        this.flows = flows;
        this.snapshot = snapshot;
        this.enabled = enabled;
        this.sink = sink;
    }

    /** Registers the listener. */
    public void start() {
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE),
                Tools.groupedThreads("onos/gw", "conflict-%d", log),
                (task, ex) -> log.warn("Compiled-flow check queue full ({}), check dropped", QUEUE));
        intents.addListener(this);
    }

    /** Unregisters the listener. */
    public void stop() {
        intents.removeListener(this);
        executor.shutdownNow();
    }

    @Override
    public boolean isRelevant(IntentEvent event) {
        return event.type() == IntentEvent.Type.INSTALLED && enabled.getAsBoolean()
                && snapshot.get().tenantOfApp(event.subject().appId()).isPresent();
    }

    @Override
    public void event(IntentEvent event) {
        Intent intent = event.subject();
        GatewaySnapshot snap = snapshot.get();
        executor.execute(() -> check(intent, snap));
    }

    /**
     * Re-checks every INSTALLED tenant intent once (conflictEnabled went false -&gt; true:
     * intents installed meanwhile never got an INSTALLED event with the check on).
     */
    public void rescanAll() {
        GatewaySnapshot snap = snapshot.get();
        int n = 0;
        for (ApplicationId app : snap.appIds().values()) {
            for (Intent i : intents.getIntentsByAppId(app)) {
                if (intents.getIntentState(i.key()) == IntentState.INSTALLED) {
                    executor.execute(() -> check(i, snap));
                    n++;
                }
            }
        }
        log.info("conflictEnabled turned on: re-checking {} installed tenant intents", n);
    }

    private void check(Intent intent, GatewaySnapshot snap) {
        try {
            Optional<Tenant> owner = snap.tenantOfApp(intent.appId());
            if (!owner.isPresent()) {
                return;
            }
            Tenant t = owner.get();
            Map<DeviceId, List<ConflictDetector.OwnedRule>> existing = TenantRules.collect(snap, flows, intents);
            for (Intent inst : intents.getInstallableIntents(intent.key())) {
                if (!(inst instanceof FlowRuleIntent)) {
                    continue;
                }
                for (FlowRule r : ((FlowRuleIntent) inst).flowRules()) {
                    Optional<Violation> v = ConflictDetector.checkDstCapture(r.selector(), t, snap.registry());
                    if (!v.isPresent()) {
                        v = ConflictDetector.checkOverlap(r, t.getId(), snap.registry(),
                                existing.getOrDefault(r.deviceId(), Collections.emptyList()),
                                ConflictDetector.COMPILED_CONFLICT);
                    }
                    if (v.isPresent()) {
                        Intent current = intents.getIntent(intent.key());
                        if (current == null || !current.id().equals(intent.id())) {
                            log.info("Intent {} changed while being checked; not withdrawing", intent.key());
                            return;
                        }
                        log.warn("Withdrawing intent {} of tenant {}: compiled flow conflicts: {}",
                                 intent.key(), t.getId(), v.get().getMessage());
                        sink.reject(t.getId(), ConflictDetector.COMPILED_CONFLICT,
                                "intent " + intent.key() + ": " + v.get().getMessage());
                        intents.withdraw(intent);
                        return;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Compiled-flow check of intent {} failed", intent.key(), e);
        }
    }
}
