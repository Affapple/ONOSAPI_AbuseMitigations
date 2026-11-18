package lu.uni.sdn.apigateway.conflict;

import com.google.common.collect.ImmutableSet;
import lombok.Value;
import lu.uni.sdn.apigateway.acl.Ownership.Violation;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthCriterion;
import org.onosproject.net.flow.criteria.IPCriterion;
import org.onosproject.net.flow.criteria.PortCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.L2ModificationInstruction;
import org.onosproject.net.flow.instructions.L3ModificationInstruction;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Conflict detection and spoofing prevention (B5). All rejections are 409.
 *
 * <ul>
 *   <li>{@code spoofed_source}: a match on ETH_SRC that is not the tenant's MAC, on
 *       ETH_SRC_MASKED (cannot be verified), or on an IPV4_SRC not inside its subnets. This
 *       covers a foreign source next to an owned IN_PORT, which B4 accepts.</li>
 *   <li>{@code foreign_rewrite}: a rewrite of ETH_SRC/ETH_DST/IPV4_SRC/IPV4_DST to an address
 *       the tenant does not own.</li>
 *   <li>{@code dst_capture}: a match on ETH_DST owned by another tenant, or an IPV4_DST prefix
 *       overlapping another tenant's subnet, even with an owned source. "Owned source to a
 *       foreign destination" could be legitimate cross-tenant communication; the plan's
 *       simplest rule rejects it for now.</li>
 *   <li>{@code overlap_conflict} / {@code compiled_conflict}: the rule can match a packet that
 *       an existing rule of another tenant on the same device also matches, at a priority
 *       &gt;= that rule's.</li>
 * </ul>
 *
 * <p><b>Overlap.</b> Field by field: absent fields are wildcards; IPv4 prefixes overlap when
 * one contains the other; every other field must be equal. On top of that, <b>source
 * ownership</b>: a rule can only carry the traffic of the tenants its source fields belong
 * to (ETH_SRC owner, tenants whose subnets meet IPV4_SRC, owner of an edge IN_PORT). A new
 * rule whose possible sources do not include tenant T cannot take T's traffic, so it never
 * conflicts with T's rules. Packet sources are taken at face value: host-level (data-plane)
 * spoofing is out of scope (southbound). This is what keeps the check from becoming a DoS:
 * a tenant's owned-source rules never conflict, whatever the other tenant has installed;
 * only rules that can carry another tenant's traffic (no owned source, i.e. what B4
 * rejects anyway) are compared field by field. With B4 on, overlap is defence in depth.</p>
 */
public final class ConflictDetector {

    public static final String SPOOFED_SOURCE = "spoofed_source";
    public static final String FOREIGN_REWRITE = "foreign_rewrite";
    public static final String DST_CAPTURE = "dst_capture";
    public static final String OVERLAP_CONFLICT = "overlap_conflict";
    public static final String COMPILED_CONFLICT = "compiled_conflict";

    /** Owner token for traffic that belongs to no tenant. */
    static final String NOBODY = "-";

    private ConflictDetector() {
    }

    /** A rule of some tenant, as installed (direct flow) or compiled from its intent. */
    @Value
    public static class OwnedRule {
        String tenantId;
        FlowRule rule;
    }

    /**
     * Spoofing check on a match.
     *
     * @param sel    selector
     * @param tenant caller's tenant
     * @return violation, if any
     */
    public static Optional<Violation> checkSources(TrafficSelector sel, Tenant tenant) {
        if (sel == null) {
            return Optional.empty();
        }
        if (sel.getCriterion(Criterion.Type.ETH_SRC_MASKED) != null) {
            return bad(SPOOFED_SOURCE, "masked ETH_SRC matches cannot be verified as tenant " + tenant.getId() + "'s");
        }
        Criterion es = sel.getCriterion(Criterion.Type.ETH_SRC);
        if (es instanceof EthCriterion && !tenant.ownsMac(((EthCriterion) es).mac())) {
            return bad(SPOOFED_SOURCE, "ETH_SRC " + ((EthCriterion) es).mac() + " is not a MAC of tenant "
                    + tenant.getId());
        }
        Criterion is = sel.getCriterion(Criterion.Type.IPV4_SRC);
        if (is instanceof IPCriterion) {
            IpPrefix p = ((IPCriterion) is).ip();
            if (tenant.getSubnets().stream().noneMatch(s -> s.contains(p))) {
                return bad(SPOOFED_SOURCE, "IPV4_SRC " + p + " is not inside tenant " + tenant.getId() + "'s subnets");
            }
        }
        return Optional.empty();
    }

    /**
     * Address rewrites must use addresses the tenant owns.
     *
     * @param t      treatment
     * @param tenant caller's tenant
     * @return violation, if any
     */
    public static Optional<Violation> checkRewrites(TrafficTreatment t, Tenant tenant) {
        if (t == null) {
            return Optional.empty();
        }
        for (Instruction i : t.allInstructions()) {
            if (i instanceof L2ModificationInstruction.ModEtherInstruction) {
                L2ModificationInstruction.ModEtherInstruction m = (L2ModificationInstruction.ModEtherInstruction) i;
                if (!tenant.ownsMac(m.mac())) {
                    return bad(FOREIGN_REWRITE, m.subtype() + " rewrite to " + m.mac() + ", not a MAC of tenant "
                            + tenant.getId());
                }
            } else if (i instanceof L3ModificationInstruction.ModIPInstruction) {
                L3ModificationInstruction.ModIPInstruction m = (L3ModificationInstruction.ModIPInstruction) i;
                if (!tenant.ownsIp(m.ip())) {
                    return bad(FOREIGN_REWRITE, m.subtype() + " rewrite to " + m.ip() + ", outside tenant "
                            + tenant.getId() + "'s subnets");
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Destination capture: matching another tenant's destination.
     *
     * @param sel    selector
     * @param tenant caller's tenant
     * @param reg    registry
     * @return violation, if any
     */
    public static Optional<Violation> checkDstCapture(TrafficSelector sel, Tenant tenant, TenantRegistry reg) {
        if (sel == null) {
            return Optional.empty();
        }
        Criterion ed = sel.getCriterion(Criterion.Type.ETH_DST);
        if (ed instanceof EthCriterion) {
            MacAddress mac = ((EthCriterion) ed).mac();
            for (Tenant o : reg.tenants().values()) {
                if (!o.getId().equals(tenant.getId()) && o.ownsMac(mac)) {
                    return bad(DST_CAPTURE, "ETH_DST " + mac + " belongs to tenant " + o.getId());
                }
            }
        }
        Criterion id = sel.getCriterion(Criterion.Type.IPV4_DST);
        if (id instanceof IPCriterion) {
            IpPrefix p = ((IPCriterion) id).ip();
            for (Tenant o : reg.tenants().values()) {
                if (!o.getId().equals(tenant.getId()) && o.getSubnets().stream().anyMatch(s -> overlaps(s, p))) {
                    return bad(DST_CAPTURE, "IPV4_DST " + p + " covers addresses of tenant " + o.getId());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Overlap with other tenants' rules on the same device.
     *
     * @param rule     new rule (device, priority, selector)
     * @param selfId   caller's tenant id
     * @param reg      registry
     * @param existing existing rules of all tenants on rule.deviceId() (own rules are ignored)
     * @param reason   reason code to report (overlap_conflict or compiled_conflict)
     * @return violation, if any
     */
    public static Optional<Violation> checkOverlap(FlowRule rule, String selfId, TenantRegistry reg,
                                                   Collection<OwnedRule> existing, String reason) {
        Set<String> mine = owners(rule.selector(), rule.deviceId(), reg);
        if (mine != null && mine.size() == 1 && mine.contains(selfId)) {
            return Optional.empty();               // can only carry its own tenant's traffic
        }
        for (OwnedRule o : existing) {
            FlowRule r = o.getRule();
            if (o.getTenantId().equals(selfId) || !r.deviceId().equals(rule.deviceId())) {
                continue;
            }
            if (mine != null && !mine.contains(o.getTenantId())) {
                continue;                          // cannot carry that tenant's traffic
            }
            Set<String> theirs = owners(r.selector(), r.deviceId(), reg);
            if (mine != null && theirs != null && disjoint(mine, theirs)) {
                continue;
            }
            if (rule.priority() >= r.priority() && fieldsOverlap(rule.selector(), r.selector())) {
                return bad(reason, "on " + rule.deviceId() + " the rule (priority " + rule.priority()
                        + ") can match traffic of tenant " + o.getTenantId() + "'s rule " + r.selector().criteria()
                        + " (priority " + r.priority() + ")");
            }
        }
        return Optional.empty();
    }

    /**
     * Whether some packet can match both selectors (absent fields are wildcards; IPv4
     * prefixes overlap when one contains the other; other fields must be equal).
     *
     * @param a selector
     * @param b selector
     * @return true if they overlap
     */
    public static boolean fieldsOverlap(TrafficSelector a, TrafficSelector b) {
        if (a == null || b == null) {
            return true;
        }
        for (Criterion ca : a.criteria()) {
            Criterion cb = b.getCriterion(ca.type());
            if (cb == null) {
                continue;
            }
            if (ca instanceof IPCriterion && cb instanceof IPCriterion) {
                if (!overlaps(((IPCriterion) ca).ip(), ((IPCriterion) cb).ip())) {
                    return false;
                }
            } else if (!ca.equals(cb)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tenants whose traffic a match can carry, from its source fields (ETH_SRC owner,
     * tenants whose subnets meet IPV4_SRC, owner of an edge IN_PORT), intersected.
     *
     * @param sel selector
     * @param dev device the rule is on (for IN_PORT)
     * @param reg registry
     * @return tenant ids ("-" = no tenant), or null if the match says nothing about the source
     */
    static Set<String> owners(TrafficSelector sel, DeviceId dev, TenantRegistry reg) {
        if (sel == null) {
            return null;
        }
        Set<String> result = null;
        Criterion es = sel.getCriterion(Criterion.Type.ETH_SRC);
        if (es instanceof EthCriterion) {
            MacAddress mac = ((EthCriterion) es).mac();
            Set<String> s = new HashSet<>();
            reg.tenants().values().stream().filter(t -> t.ownsMac(mac)).forEach(t -> s.add(t.getId()));
            result = intersect(result, s.isEmpty() ? ImmutableSet.of(NOBODY) : s);
        }
        Criterion is = sel.getCriterion(Criterion.Type.IPV4_SRC);
        if (is instanceof IPCriterion) {
            IpPrefix p = ((IPCriterion) is).ip();
            Set<String> s = new HashSet<>();
            boolean inside = false;
            for (Tenant t : reg.tenants().values()) {
                for (IpPrefix sub : t.getSubnets()) {
                    if (overlaps(sub, p)) {
                        s.add(t.getId());
                    }
                    inside |= sub.contains(p);
                }
            }
            if (!inside) {
                s.add(NOBODY);
            }
            result = intersect(result, s);
        }
        Criterion ip = sel.getCriterion(Criterion.Type.IN_PORT);
        if (ip instanceof PortCriterion && dev != null) {
            ConnectPoint cp = new ConnectPoint(dev, ((PortCriterion) ip).port());
            for (Tenant t : reg.tenants().values()) {
                if (t.ownsEdgePort(cp)) {
                    result = intersect(result, ImmutableSet.of(t.getId()));
                }
            }
        }
        return result;
    }

    private static Set<String> intersect(Set<String> acc, Set<String> s) {
        if (acc == null) {
            return new HashSet<>(s);
        }
        acc.retainAll(s);
        return acc;
    }

    private static boolean disjoint(Set<String> a, Set<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlaps(IpPrefix a, IpPrefix b) {
        return a.contains(b) || b.contains(a);
    }

    private static Optional<Violation> bad(String reason, String message) {
        return Optional.of(new Violation(reason, message));
    }
}
