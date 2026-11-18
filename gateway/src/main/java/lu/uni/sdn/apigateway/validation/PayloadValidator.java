package lu.uni.sdn.apigateway.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import lu.uni.sdn.apigateway.acl.Ownership.Violation;
import lu.uni.sdn.apigateway.tenant.Tenant;
import org.onlab.packet.IpPrefix;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthCriterion;
import org.onosproject.net.flow.criteria.EthTypeCriterion;
import org.onosproject.net.flow.criteria.IPCriterion;
import org.onosproject.net.flow.criteria.IPProtocolCriterion;
import org.onosproject.net.flow.criteria.PortCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.flow.instructions.L2ModificationInstruction;
import org.onosproject.net.flow.instructions.L3ModificationInstruction;
import org.onosproject.net.intent.ConnectivityIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.PointToPointIntent;

import java.util.Optional;
import java.util.Set;

/**
 * Payload validation (B4): rules about what a tenant's flow/intent MEANS, checked
 * against the tenant registry. Every rejection is 400 with its own reason code.
 *
 * <p>Split with the other layers: <b>B1 (ACL, 403)</b> decides WHERE a rule may act
 * (device, IN_PORT/OUTPUT ports, allowed instruction types, intent endpoints).
 * <b>B4 (this, 400)</b> decides whether the rule is well-formed and confined to the
 * tenant's own traffic (priority, timeouts, criterion allowlist, owned source in the
 * match, no CONTROLLER output, P2P selector, intent rewrites). <b>B5 (409)</b> decides
 * whether it collides with ANOTHER tenant: spoofed foreign sources next to an owned
 * IN_PORT, flow rewrites, destination capture, overlap with another tenant's rules.</p>
 *
 * <p>Flows:</p>
 * <ol>
 *   <li>{@code bad_priority}: priority outside [minPriority, maxPriority];</li>
 *   <li>{@code permanent_not_allowed}: isPermanent;</li>
 *   <li>{@code bad_timeout}: idle timeout not in [1, maxTimeout], or hard timeout above maxTimeout;</li>
 *   <li>{@code criterion_not_allowed}: a match field outside the L2-L4 allowlist;
 *       {@code missing_prerequisite}: IPv4/L4 fields without ETH_TYPE=0x0800, TCP/UDP/ICMP
 *       fields without the matching IP_PROTO (6/17/1);</li>
 *   <li>{@code controller_output}: OUTPUT to CONTROLLER (packet-ins);</li>
 *   <li>{@code drop_not_owned} / {@code match_not_owned}: the match does not confine the rule to
 *       the tenant's own traffic, i.e. none of: IN_PORT = one of its edge ports on that device,
 *       ETH_SRC = one of its MACs, IPV4_SRC inside one of its subnets. Catches catch-all,
 *       ETH_TYPE-only, trunk-IN_PORT-only and destination-only matches (drop rules get the
 *       drop_ reason).</li>
 * </ol>
 * <p>Intents (H2H/P2P only; endpoints already checked by B1):</p>
 * <ol>
 *   <li>{@code bad_priority};</li>
 *   <li>{@code criterion_not_allowed} / {@code missing_prerequisite} in the selector;
 *       {@code constraints_not_allowed}: any tenant-supplied "constraints" (checked on the JSON);</li>
 *   <li>{@code selector_not_owned}: a PointToPointIntent's selector must contain an owned ETH_SRC
 *       or an IPV4_SRC inside an owned subnet. Otherwise its compiled IN_PORT-only rules on
 *       the transit switches capture every tenant's traffic arriving on that trunk.
 *       HostToHostIntents compile to IN_PORT+ETH_SRC+ETH_DST with owned MACs and need no
 *       selector;</li>
 *   <li>{@code rewrite_not_owned}: a treatment rewriting ETH_SRC/ETH_DST/IPV4_SRC/IPV4_DST to an
 *       address the tenant does not own; {@code instruction_not_allowed}: any treatment
 *       instruction other than L2/L3/L4 header rewrites.</li>
 * </ol>
 */
public final class PayloadValidator {

    public static final String BAD_PRIORITY = "bad_priority";
    public static final String PERMANENT_NOT_ALLOWED = "permanent_not_allowed";
    public static final String BAD_TIMEOUT = "bad_timeout";
    public static final String CRITERION_NOT_ALLOWED = "criterion_not_allowed";
    public static final String CONTROLLER_OUTPUT = "controller_output";
    public static final String MATCH_NOT_OWNED = "match_not_owned";
    public static final String DROP_NOT_OWNED = "drop_not_owned";
    public static final String SELECTOR_NOT_OWNED = "selector_not_owned";
    public static final String REWRITE_NOT_OWNED = "rewrite_not_owned";
    public static final String INSTRUCTION_NOT_ALLOWED = "instruction_not_allowed";
    public static final String MISSING_PREREQUISITE = "missing_prerequisite";
    public static final String CONSTRAINTS_NOT_ALLOWED = "constraints_not_allowed";

    /** Fields that are only valid on IPv4 packets (OpenFlow prerequisite ETH_TYPE=0x0800). */
    private static final Set<Criterion.Type> NEEDS_IPV4 = ImmutableSet.of(Criterion.Type.IPV4_SRC,
            Criterion.Type.IPV4_DST, Criterion.Type.IP_PROTO, Criterion.Type.IP_DSCP, Criterion.Type.ICMPV4_TYPE,
            Criterion.Type.ICMPV4_CODE, Criterion.Type.TCP_SRC, Criterion.Type.TCP_DST, Criterion.Type.UDP_SRC,
            Criterion.Type.UDP_DST);
    private static final short ETH_TYPE_IPV4 = 0x0800;
    private static final short PROTO_ICMP = 1;
    private static final short PROTO_TCP = 6;
    private static final short PROTO_UDP = 17;

    /** Match fields a tenant may use (L2-L4 headers; no metadata, tunnels, MPLS, IPv6, ARP). */
    public static final Set<Criterion.Type> ALLOWED_CRITERIA = ImmutableSet.of(
            Criterion.Type.IN_PORT, Criterion.Type.ETH_SRC, Criterion.Type.ETH_DST, Criterion.Type.ETH_TYPE,
            Criterion.Type.VLAN_VID, Criterion.Type.VLAN_PCP, Criterion.Type.IP_PROTO, Criterion.Type.IP_DSCP,
            Criterion.Type.IPV4_SRC, Criterion.Type.IPV4_DST, Criterion.Type.TCP_SRC, Criterion.Type.TCP_DST,
            Criterion.Type.UDP_SRC, Criterion.Type.UDP_DST, Criterion.Type.ICMPV4_TYPE, Criterion.Type.ICMPV4_CODE);

    private PayloadValidator() {
    }

    /**
     * Validates a decoded flow rule of {@code tenant}.
     *
     * @param rule   flow rule
     * @param tenant caller's tenant
     * @param lim    limits
     * @return violation, if any
     */
    public static Optional<Violation> checkFlow(FlowRule rule, Tenant tenant, ValidationLimits lim) {
        Optional<Violation> v = priority(rule.priority(), lim);
        if (v.isPresent()) {
            return v;
        }
        if (rule.isPermanent()) {
            return bad(PERMANENT_NOT_ALLOWED, "permanent rules are not allowed (use isPermanent=false and a timeout)");
        }
        if (rule.timeout() < 1 || rule.timeout() > lim.getMaxTimeout()) {
            return bad(BAD_TIMEOUT, "timeout " + rule.timeout() + " s is outside [1, " + lim.getMaxTimeout() + "]");
        }
        if (rule.hardTimeout() > lim.getMaxTimeout()) {
            return bad(BAD_TIMEOUT, "hardTimeout " + rule.hardTimeout() + " s exceeds " + lim.getMaxTimeout());
        }
        v = criteria(rule.selector());
        if (v.isPresent()) {
            return v;
        }
        TrafficTreatment t = rule.treatment();
        boolean outputs = false;
        if (t != null) {
            for (Instruction i : t.allInstructions()) {
                if (i.type() == Instruction.Type.OUTPUT) {
                    outputs = true;
                    if (PortNumber.CONTROLLER.equals(((Instructions.OutputInstruction) i).port())) {
                        return bad(CONTROLLER_OUTPUT, "output to CONTROLLER is not allowed (it generates packet-ins)");
                    }
                }
            }
        }
        if (!ownedSource(rule.selector(), rule.deviceId(), tenant, true)) {
            return outputs
                    ? bad(MATCH_NOT_OWNED, "the match must confine the rule to tenant " + tenant.getId()
                            + "'s traffic: IN_PORT on its edge port, or its ETH_SRC, or IPV4_SRC in its subnets")
                    : bad(DROP_NOT_OWNED, "a drop rule must match only tenant " + tenant.getId()
                            + "'s traffic: IN_PORT on its edge port, or its ETH_SRC, or IPV4_SRC in its subnets");
        }
        return Optional.empty();
    }

    /**
     * Validates a decoded intent of {@code tenant}.
     *
     * @param intent intent
     * @param tenant caller's tenant
     * @param lim    limits
     * @return violation, if any
     */
    public static Optional<Violation> checkIntent(Intent intent, Tenant tenant, ValidationLimits lim) {
        Optional<Violation> v = priority(intent.priority(), lim);
        if (v.isPresent()) {
            return v;
        }
        if (!(intent instanceof ConnectivityIntent)) {
            return Optional.empty();   // B1 already rejected every type other than H2H/P2P
        }
        ConnectivityIntent ci = (ConnectivityIntent) intent;
        v = criteria(ci.selector());
        if (v.isPresent()) {
            return v;
        }
        if (intent instanceof PointToPointIntent && !ownedSource(ci.selector(), null, tenant, false)) {
            return bad(SELECTOR_NOT_OWNED, "a PointToPointIntent selector must contain tenant " + tenant.getId()
                    + "'s ETH_SRC or an IPV4_SRC inside its subnets; otherwise its compiled transit rules "
                    + "(IN_PORT only) capture every tenant's traffic on the trunk");
        }
        return rewrites(ci.treatment(), tenant);
    }

    private static Optional<Violation> priority(int p, ValidationLimits lim) {
        if (p < lim.getMinPriority() || p > lim.getMaxPriority()) {
            return bad(BAD_PRIORITY, "priority " + p + " is outside [" + lim.getMinPriority() + ", "
                    + lim.getMaxPriority() + "]");
        }
        return Optional.empty();
    }

    private static Optional<Violation> criteria(TrafficSelector sel) {
        if (sel == null) {
            return Optional.empty();
        }
        for (Criterion c : sel.criteria()) {
            if (!ALLOWED_CRITERIA.contains(c.type())) {
                return bad(CRITERION_NOT_ALLOWED, "match field " + c.type() + " is not allowed");
            }
        }
        return prerequisites(sel);
    }

    /**
     * OpenFlow match prerequisites. Verified live: OVS answers OFBadMatch BAD_PREREQ to
     * IPV4_SRC without ETH_TYPE=IPv4, and ONOS leaves the rule in PENDING_ADD, retrying.
     */
    private static Optional<Violation> prerequisites(TrafficSelector sel) {
        Criterion et = sel.getCriterion(Criterion.Type.ETH_TYPE);
        boolean ipv4 = et instanceof EthTypeCriterion
                && ((EthTypeCriterion) et).ethType().toShort() == ETH_TYPE_IPV4;
        for (Criterion c : sel.criteria()) {
            if (NEEDS_IPV4.contains(c.type()) && !ipv4) {
                return bad(MISSING_PREREQUISITE, c.type() + " requires ETH_TYPE=0x0800 in the match");
            }
        }
        Criterion pc = sel.getCriterion(Criterion.Type.IP_PROTO);
        short proto = pc instanceof IPProtocolCriterion ? ((IPProtocolCriterion) pc).protocol() : -1;
        Optional<Violation> v = needsProto(sel, proto, PROTO_TCP, Criterion.Type.TCP_SRC, Criterion.Type.TCP_DST);
        if (!v.isPresent()) {
            v = needsProto(sel, proto, PROTO_UDP, Criterion.Type.UDP_SRC, Criterion.Type.UDP_DST);
        }
        if (!v.isPresent()) {
            v = needsProto(sel, proto, PROTO_ICMP, Criterion.Type.ICMPV4_TYPE, Criterion.Type.ICMPV4_CODE);
        }
        return v;
    }

    private static Optional<Violation> needsProto(TrafficSelector sel, short proto, short want,
                                                  Criterion.Type... types) {
        for (Criterion.Type t : types) {
            if (sel.getCriterion(t) != null && proto != want) {
                return bad(MISSING_PREREQUISITE, t + " requires IP_PROTO=" + want + " in the match");
            }
        }
        return Optional.empty();
    }

    /**
     * Checks the raw intent JSON: tenants may not send intent constraints.
     *
     * @param json intent request body
     * @return violation, if any
     */
    public static Optional<Violation> checkIntentJson(JsonNode json) {
        JsonNode c = json == null ? null : json.get("constraints");
        if (c != null && !c.isNull() && !(c.isArray() && c.size() == 0)) {
            return bad(CONSTRAINTS_NOT_ALLOWED, "intent constraints are not allowed for tenants");
        }
        return Optional.empty();
    }

    /**
     * Whether the selector confines traffic to the tenant: owned ETH_SRC, IPV4_SRC
     * inside an owned subnet, or (flows only) IN_PORT on an owned edge port.
     */
    private static boolean ownedSource(TrafficSelector sel, DeviceId dev, Tenant tenant, boolean allowInPort) {
        if (sel == null) {
            return false;
        }
        Criterion inPort = sel.getCriterion(Criterion.Type.IN_PORT);
        if (allowInPort && dev != null && inPort instanceof PortCriterion
                && tenant.ownsEdgePort(new ConnectPoint(dev, ((PortCriterion) inPort).port()))) {
            return true;
        }
        Criterion ethSrc = sel.getCriterion(Criterion.Type.ETH_SRC);
        if (ethSrc instanceof EthCriterion && tenant.ownsMac(((EthCriterion) ethSrc).mac())) {
            return true;
        }
        Criterion ipSrc = sel.getCriterion(Criterion.Type.IPV4_SRC);
        if (ipSrc instanceof IPCriterion) {
            IpPrefix p = ((IPCriterion) ipSrc).ip();
            return tenant.getSubnets().stream().anyMatch(s -> s.contains(p));
        }
        return false;
    }

    private static Optional<Violation> rewrites(TrafficTreatment t, Tenant tenant) {
        if (t == null) {
            return Optional.empty();
        }
        for (Instruction i : t.allInstructions()) {
            switch (i.type()) {
                case L2MODIFICATION:
                    if (i instanceof L2ModificationInstruction.ModEtherInstruction) {
                        L2ModificationInstruction.ModEtherInstruction m =
                                (L2ModificationInstruction.ModEtherInstruction) i;
                        if (!tenant.ownsMac(m.mac())) {
                            return bad(REWRITE_NOT_OWNED, m.subtype() + " rewrite to " + m.mac()
                                    + ", which tenant " + tenant.getId() + " does not own");
                        }
                    }
                    break;
                case L3MODIFICATION:
                    if (i instanceof L3ModificationInstruction.ModIPInstruction) {
                        L3ModificationInstruction.ModIPInstruction m = (L3ModificationInstruction.ModIPInstruction) i;
                        if (!tenant.ownsIp(m.ip())) {
                            return bad(REWRITE_NOT_OWNED, m.subtype() + " rewrite to " + m.ip()
                                    + ", which is outside tenant " + tenant.getId() + "'s subnets");
                        }
                    }
                    break;
                case L4MODIFICATION:
                case NOACTION:          // ONOS puts NOACTION in every empty treatment
                    break;
                default:
                    return bad(INSTRUCTION_NOT_ALLOWED, "intent treatments may only rewrite headers, not "
                            + i.type());
            }
        }
        return Optional.empty();
    }

    private static Optional<Violation> bad(String reason, String message) {
        return Optional.of(new Violation(reason, message));
    }
}
