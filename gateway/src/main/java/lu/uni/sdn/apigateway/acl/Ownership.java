package lu.uni.sdn.apigateway.acl;

import com.google.common.collect.ImmutableSet;
import lombok.Value;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.HostId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.PortCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.flow.instructions.L2ModificationInstruction;
import org.onosproject.net.flow.instructions.L3ModificationInstruction;
import org.onosproject.net.intent.HostToHostIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.PointToPointIntent;

import java.util.Optional;
import java.util.Set;

/**
 * Ownership checks of the ACL layer (plan B1): a flow's device and ports, and an
 * intent's endpoints, must belong to the caller's tenant. This is deliberately
 * only about <em>where</em> a rule acts, not about what it matches: broad matches,
 * spoofed sources and priorities are layer 2 (B4/B5).
 *
 * <p>Flow rules:</p>
 * <ul>
 *   <li>the device must be in the registry (it carries a tenant edge port or a trunk);</li>
 *   <li>an IN_PORT match must be the tenant's own edge port or a trunk port on that device;</li>
 *   <li>every OUTPUT port must be the tenant's own edge port or a trunk port on that device.
 *       Logical ports: IN_PORT, TABLE and CONTROLLER pass here (CONTROLLER is a B4 concern);
 *       FLOOD, ALL, NORMAL, LOCAL and ANY are rejected because they reach ports the tenant
 *       does not own;</li>
 *   <li>treatment allowlist: OUTPUT, NOACTION, L2 rewrites (ETH_SRC/DST, VLAN_ID/PCP,
 *       VLAN_PUSH/POP), L3 rewrites (IPV4_SRC/DST, DEC_TTL, IP_DSCP), L4 port rewrites.
 *       GROUP/METER/QUEUE give resource_not_owned; anything else (TABLE, METADATA,
 *       STAT_TRIGGER, MPLS, tunnel, extension, ...) gives instruction_not_allowed.</li>
 * </ul>
 * <p>Intents: only HostToHostIntent (both hosts' MACs belong to the tenant) and
 * PointToPointIntent (ingress and egress are the tenant's edge ports).</p>
 */
public final class Ownership {

    private static final Set<PortNumber> ALLOWED_LOGICAL_OUTPUTS =
            ImmutableSet.of(PortNumber.IN_PORT, PortNumber.TABLE, PortNumber.CONTROLLER);

    /** L2 rewrites a tenant may use (whose addresses they may use is B5's spoofing check). */
    private static final Set<L2ModificationInstruction.L2SubType> ALLOWED_L2 = ImmutableSet.of(
            L2ModificationInstruction.L2SubType.ETH_SRC, L2ModificationInstruction.L2SubType.ETH_DST,
            L2ModificationInstruction.L2SubType.VLAN_ID, L2ModificationInstruction.L2SubType.VLAN_PCP,
            L2ModificationInstruction.L2SubType.VLAN_PUSH, L2ModificationInstruction.L2SubType.VLAN_POP);

    /** L3 rewrites a tenant may use. */
    private static final Set<L3ModificationInstruction.L3SubType> ALLOWED_L3 = ImmutableSet.of(
            L3ModificationInstruction.L3SubType.IPV4_SRC, L3ModificationInstruction.L3SubType.IPV4_DST,
            L3ModificationInstruction.L3SubType.DEC_TTL, L3ModificationInstruction.L3SubType.IP_DSCP);

    private Ownership() {
    }

    /** A failed ownership check: reason code + human-readable message. */
    @Value
    public static class Violation {
        String reason;
        String message;
    }

    /**
     * Checks a decoded flow rule.
     *
     * @param rule   flow rule
     * @param tenant caller's tenant
     * @param reg    registry
     * @return violation, if any
     */
    public static Optional<Violation> checkFlow(FlowRule rule, Tenant tenant, TenantRegistry reg) {
        DeviceId dev = rule.deviceId();
        if (!reg.knownDevices().contains(dev)) {
            return v(Reasons.DEVICE_NOT_OWNED, "device " + dev + " is not part of the tenant network");
        }
        if (rule.selector() != null) {
            Criterion c = rule.selector().getCriterion(Criterion.Type.IN_PORT);
            if (c instanceof PortCriterion) {
                PortNumber p = ((PortCriterion) c).port();
                if (!usablePort(new ConnectPoint(dev, p), tenant, reg)) {
                    return v(Reasons.PORT_NOT_OWNED, "IN_PORT " + dev + "/" + p
                            + " is neither an edge port of tenant " + tenant.getId() + " nor a trunk port");
                }
            }
            if (rule.selector().getCriterion(Criterion.Type.IN_PHY_PORT) != null) {
                return v(Reasons.PORT_NOT_OWNED, "IN_PHY_PORT matches are not allowed");
            }
        }
        if (rule.treatment() != null) {
            Optional<Violation> tv = checkTreatment(rule.treatment(), dev, tenant, reg);
            if (tv.isPresent()) {
                return tv;
            }
        }
        return Optional.empty();
    }

    /**
     * Checks a decoded intent.
     *
     * @param intent intent
     * @param tenant caller's tenant
     * @return violation, if any
     */
    public static Optional<Violation> checkIntent(Intent intent, Tenant tenant) {
        if (intent instanceof HostToHostIntent) {
            HostToHostIntent h = (HostToHostIntent) intent;
            for (HostId id : new HostId[]{h.one(), h.two()}) {
                if (!tenant.ownsMac(id.mac())) {
                    return v(Reasons.ENDPOINT_NOT_OWNED, "host " + id + " is not a host of tenant "
                            + tenant.getId());
                }
            }
            return Optional.empty();
        }
        if (intent instanceof PointToPointIntent) {
            PointToPointIntent p = (PointToPointIntent) intent;
            for (ConnectPoint cp : new ConnectPoint[]{p.filteredIngressPoint().connectPoint(),
                    p.filteredEgressPoint().connectPoint()}) {
                if (!tenant.ownsEdgePort(cp)) {
                    return v(Reasons.ENDPOINT_NOT_OWNED, "endpoint " + cp + " is not an edge port of tenant "
                            + tenant.getId());
                }
            }
            return Optional.empty();
        }
        return v(Reasons.UNSUPPORTED_INTENT_TYPE, "intent type " + intent.getClass().getSimpleName()
                + " is not supported (HostToHostIntent, PointToPointIntent only)");
    }

    /**
     * Treatment allowlist. Note that ONOS keeps meters, table transitions, write
     * metadata and stat triggers OUTSIDE {@code allInstructions()} (which is only
     * immediate + deferred), so they are checked separately.
     */
    private static Optional<Violation> checkTreatment(TrafficTreatment t, DeviceId dev, Tenant tenant,
                                                      TenantRegistry reg) {
        if (!t.meters().isEmpty() || t.metered() != null) {
            return v(Reasons.RESOURCE_NOT_OWNED, "METER instructions are not allowed: tenants own no meters");
        }
        if (t.tableTransition() != null) {
            return v(Reasons.INSTRUCTION_NOT_ALLOWED, "TABLE (goto-table) instructions are not allowed");
        }
        if (t.writeMetadata() != null) {
            return v(Reasons.INSTRUCTION_NOT_ALLOWED, "METADATA instructions are not allowed");
        }
        if (t.statTrigger() != null) {
            return v(Reasons.INSTRUCTION_NOT_ALLOWED, "STAT_TRIGGER instructions are not allowed");
        }
        for (Instruction i : t.allInstructions()) {
            switch (i.type()) {
                case NOACTION:
                    break;
                case OUTPUT:
                    PortNumber p = ((Instructions.OutputInstruction) i).port();
                    if (p.isLogical()) {
                        if (!ALLOWED_LOGICAL_OUTPUTS.contains(p)) {
                            return v(Reasons.PORT_NOT_OWNED, "output to logical port " + p
                                    + " reaches ports tenant " + tenant.getId() + " does not own");
                        }
                    } else if (!usablePort(new ConnectPoint(dev, p), tenant, reg)) {
                        return v(Reasons.PORT_NOT_OWNED, "output port " + dev + "/" + p
                                + " is neither an edge port of tenant " + tenant.getId() + " nor a trunk port");
                    }
                    break;
                case L2MODIFICATION:
                    if (!ALLOWED_L2.contains(((L2ModificationInstruction) i).subtype())) {
                        return v(Reasons.INSTRUCTION_NOT_ALLOWED, "L2 modification "
                                + ((L2ModificationInstruction) i).subtype() + " is not allowed");
                    }
                    break;
                case L3MODIFICATION:
                    if (!ALLOWED_L3.contains(((L3ModificationInstruction) i).subtype())) {
                        return v(Reasons.INSTRUCTION_NOT_ALLOWED, "L3 modification "
                                + ((L3ModificationInstruction) i).subtype() + " is not allowed");
                    }
                    break;
                case L4MODIFICATION:
                    break;  // TCP/UDP src/dst port rewrites only (all L4 subtypes)
                case GROUP:
                case METER:
                case QUEUE:
                    return v(Reasons.RESOURCE_NOT_OWNED, i.type() + " instructions are not allowed: "
                            + "tenants own no groups, meters or queues");
                default:
                    return v(Reasons.INSTRUCTION_NOT_ALLOWED, i.type() + " instructions are not allowed");
            }
        }
        return Optional.empty();
    }

    private static boolean usablePort(ConnectPoint cp, Tenant tenant, TenantRegistry reg) {
        return tenant.ownsEdgePort(cp) || reg.isTrunk(cp);
    }

    private static Optional<Violation> v(String reason, String message) {
        return Optional.of(new Violation(reason, message));
    }
}
