package lu.uni.sdn.apigateway.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import lu.uni.sdn.apigateway.quota.QuotaLimits;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Immutable tenant registry parsed from tenants.json (schema in plans/AGENTS.md).
 *
 * <p>Parsing is strict: any schema violation or cross-tenant inconsistency makes
 * {@link #fromJson(JsonNode)} throw {@link IllegalArgumentException} with a message
 * naming the offending field, and the caller keeps its previous registry.</p>
 */
public final class TenantRegistry {

    /** Prefix of the per-tenant ApplicationId names. */
    public static final String TENANT_APP_PREFIX = "lu.uni.sdn.gw.";

    private static final Pattern TENANT_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Set<String> ROOT_FIELDS =
            ImmutableSet.of("tenants", "trunkPorts", "admins", "serviceAccount");
    private static final Set<String> TENANT_FIELDS =
            ImmutableSet.of("subnets", "hosts", "edgePorts", "accounts", "quotas");
    private static final Set<String> HOST_FIELDS =
            ImmutableSet.of("name", "mac", "ip", "location");

    private static final TenantRegistry EMPTY = new TenantRegistry(
            ImmutableMap.of(), ImmutableSet.of(), ImmutableSet.of(), null);

    private final ImmutableMap<String, Tenant> tenants;
    private final ImmutableSet<ConnectPoint> trunkPorts;
    private final ImmutableSet<String> admins;
    private final String serviceAccount;
    /** user -> tenant, derived. */
    private final ImmutableMap<String, Tenant> tenantByUser;
    /** Devices that carry a tenant edge port or a trunk port, derived. */
    private final ImmutableSet<DeviceId> knownDevices;

    private TenantRegistry(ImmutableMap<String, Tenant> tenants, ImmutableSet<ConnectPoint> trunkPorts,
                           ImmutableSet<String> admins, String serviceAccount) {
        this.tenants = tenants;
        this.trunkPorts = trunkPorts;
        this.admins = admins;
        this.serviceAccount = serviceAccount;
        ImmutableMap.Builder<String, Tenant> b = ImmutableMap.builder();
        tenants.values().forEach(t -> t.getAccounts().keySet().forEach(u -> b.put(u, t)));
        this.tenantByUser = b.build();
        ImmutableSet.Builder<DeviceId> d = ImmutableSet.builder();
        trunkPorts.forEach(cp -> d.add(cp.deviceId()));
        tenants.values().forEach(t -> t.getEdgePorts().forEach(cp -> d.add(cp.deviceId())));
        this.knownDevices = d.build();
    }

    /**
     * Returns the registry with no tenants, no admins and no service account
     * (used before tenants.json has been loaded; everybody resolves to NONE).
     *
     * @return empty registry
     */
    public static TenantRegistry empty() {
        return EMPTY;
    }

    /**
     * Tenants by id, in file order.
     *
     * @return tenants
     */
    public ImmutableMap<String, Tenant> tenants() {
        return tenants;
    }

    /**
     * Looks up a tenant by id.
     *
     * @param id tenant id
     * @return tenant, if any
     */
    public Optional<Tenant> tenant(String id) {
        return Optional.ofNullable(tenants.get(id));
    }

    /**
     * Switch-to-switch ports.
     *
     * @return trunk ports
     */
    public ImmutableSet<ConnectPoint> trunkPorts() {
        return trunkPorts;
    }

    /**
     * Devices the registry knows about: every device with a tenant edge port or
     * a trunk port. Tenants share these switches.
     *
     * @return device ids
     */
    public ImmutableSet<DeviceId> knownDevices() {
        return knownDevices;
    }

    /**
     * Whether the connect point is a switch-to-switch port.
     *
     * @param cp connect point
     * @return true if trunk
     */
    public boolean isTrunk(ConnectPoint cp) {
        return trunkPorts.contains(cp);
    }

    /**
     * Gateway admin user names.
     *
     * @return admins
     */
    public ImmutableSet<String> admins() {
        return admins;
    }

    /**
     * Read-forwarding service account user name.
     *
     * @return user name, or null in the empty registry
     */
    public String serviceAccount() {
        return serviceAccount;
    }

    /**
     * Returns the tenant a Karaf user belongs to.
     *
     * @param user Karaf user name
     * @return tenant, if the user is a tenant account
     */
    public Optional<Tenant> tenantOf(String user) {
        return Optional.ofNullable(user == null ? null : tenantByUser.get(user));
    }

    /**
     * Returns the gateway role of a Karaf user.
     *
     * @param user Karaf user name (may be null)
     * @return gateway role; NONE for unknown users
     */
    public GatewayRole roleOf(String user) {
        if (user == null) {
            return GatewayRole.NONE;
        }
        if (admins.contains(user)) {
            return GatewayRole.ADMIN;
        }
        if (user.equals(serviceAccount)) {
            return GatewayRole.SERVICE;
        }
        Tenant t = tenantByUser.get(user);
        return t == null ? GatewayRole.NONE : t.getAccounts().get(user);
    }

    /**
     * Parses and validates a tenants.json document.
     *
     * @param root parsed JSON
     * @return registry
     * @throws IllegalArgumentException on any schema or consistency error
     */
    public static TenantRegistry fromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("tenants.json: root must be an object");
        }
        onlyFields(root, ROOT_FIELDS, "root");

        ImmutableSet<String> admins = ImmutableSet.copyOf(
                stringList(required(root, "admins", "root"), "admins", Function.identity()));
        String serviceAccount = text(required(root, "serviceAccount", "root"), "serviceAccount");
        if (admins.contains(serviceAccount)) {
            throw new IllegalArgumentException("serviceAccount \"" + serviceAccount + "\" is also an admin");
        }
        ImmutableSet<ConnectPoint> trunkPorts = ImmutableSet.copyOf(
                stringList(required(root, "trunkPorts", "root"), "trunkPorts", TenantRegistry::connectPoint));

        JsonNode tenantsNode = required(root, "tenants", "root");
        if (!tenantsNode.isObject()) {
            throw new IllegalArgumentException("tenants: must be an object keyed by tenant id");
        }

        Map<String, Tenant> tenants = new LinkedHashMap<>();
        Map<String, String> userOwner = new HashMap<>();
        Map<ConnectPoint, String> portOwner = new HashMap<>();
        Map<MacAddress, String> macOwner = new HashMap<>();
        Map<IpAddress, String> ipOwner = new HashMap<>();
        Map<IpPrefix, String> subnetOwner = new HashMap<>();

        Iterator<Map.Entry<String, JsonNode>> it = tenantsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            Tenant t = parseTenant(e.getKey(), e.getValue());

            for (String user : t.getAccounts().keySet()) {
                if (admins.contains(user) || user.equals(serviceAccount)) {
                    throw new IllegalArgumentException("tenants." + t.getId() + ".accounts: \"" + user
                            + "\" is also an admin or the service account");
                }
                claim(userOwner, user, t.getId(), "account");
            }
            for (ConnectPoint cp : t.getEdgePorts()) {
                if (trunkPorts.contains(cp)) {
                    throw new IllegalArgumentException("tenants." + t.getId() + ".edgePorts: " + cp
                            + " is also a trunk port");
                }
                claim(portOwner, cp, t.getId(), "edge port");
            }
            for (TenantHost h : t.getHosts()) {
                claim(macOwner, h.getMac(), t.getId(), "host MAC");
                claim(ipOwner, h.getIp(), t.getId(), "host IP");
            }
            for (IpPrefix p : t.getSubnets()) {
                for (Map.Entry<IpPrefix, String> other : subnetOwner.entrySet()) {
                    if (!other.getValue().equals(t.getId())
                            && (other.getKey().contains(p) || p.contains(other.getKey()))) {
                        throw new IllegalArgumentException("subnet " + p + " of tenant " + t.getId()
                                + " overlaps " + other.getKey() + " of tenant " + other.getValue());
                    }
                }
                subnetOwner.put(p, t.getId());
            }
            tenants.put(t.getId(), t);
        }
        return new TenantRegistry(ImmutableMap.copyOf(tenants), trunkPorts, admins, serviceAccount);
    }

    private static Tenant parseTenant(String id, JsonNode n) {
        String where = "tenants." + id;
        if (!TENANT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(where + ": tenant id must match " + TENANT_ID.pattern());
        }
        if (!n.isObject()) {
            throw new IllegalArgumentException(where + ": must be an object");
        }
        onlyFields(n, TENANT_FIELDS, where);

        List<IpPrefix> subnets = stringList(required(n, "subnets", where), where + ".subnets",
                TenantRegistry::ipPrefix);
        if (subnets.isEmpty()) {
            throw new IllegalArgumentException(where + ".subnets: must not be empty");
        }
        ImmutableSet<ConnectPoint> edgePorts = ImmutableSet.copyOf(
                stringList(required(n, "edgePorts", where), where + ".edgePorts", TenantRegistry::connectPoint));

        JsonNode hostsNode = required(n, "hosts", where);
        if (!hostsNode.isArray()) {
            throw new IllegalArgumentException(where + ".hosts: must be an array");
        }
        ImmutableList.Builder<TenantHost> hosts = ImmutableList.builder();
        Set<String> hostNames = new HashSet<>();
        int i = 0;
        for (JsonNode h : hostsNode) {
            String hw = where + ".hosts[" + i++ + "]";
            if (!h.isObject()) {
                throw new IllegalArgumentException(hw + ": must be an object");
            }
            onlyFields(h, HOST_FIELDS, hw);
            String name = text(required(h, "name", hw), hw + ".name");
            MacAddress mac = parse(text(required(h, "mac", hw), hw + ".mac"), hw + ".mac", MacAddress::valueOf);
            IpAddress ip = parse(text(required(h, "ip", hw), hw + ".ip"), hw + ".ip", IpAddress::valueOf);
            ConnectPoint loc = parse(text(required(h, "location", hw), hw + ".location"), hw + ".location",
                    TenantRegistry::connectPoint);
            if (!hostNames.add(name)) {
                throw new IllegalArgumentException(hw + ".name: duplicate host name " + name);
            }
            if (subnets.stream().noneMatch(p -> p.contains(ip))) {
                throw new IllegalArgumentException(hw + ".ip: " + ip + " is outside the tenant's subnets");
            }
            if (!edgePorts.contains(loc)) {
                throw new IllegalArgumentException(hw + ".location: " + loc + " is not one of the tenant's edgePorts");
            }
            hosts.add(new TenantHost(name, mac, ip, loc));
        }

        JsonNode accNode = required(n, "accounts", where);
        if (!accNode.isObject()) {
            throw new IllegalArgumentException(where + ".accounts: must be an object user -> role");
        }
        ImmutableMap.Builder<String, GatewayRole> accounts = ImmutableMap.builder();
        accNode.fields().forEachRemaining(a -> {
            String aw = where + ".accounts." + a.getKey();
            accounts.put(a.getKey(), parse(text(a.getValue(), aw), aw, GatewayRole::parseAccountRole));
        });

        ImmutableMap.Builder<String, Long> quotas = ImmutableMap.builder();
        JsonNode qNode = n.get("quotas");
        if (qNode != null && !qNode.isNull()) {
            if (!qNode.isObject()) {
                throw new IllegalArgumentException(where + ".quotas: must be an object name -> number");
            }
            qNode.fields().forEachRemaining(q -> {
                if (!QuotaLimits.KEYS.contains(q.getKey())) {
                    throw new IllegalArgumentException(where + ".quotas: unknown quota \"" + q.getKey()
                            + "\" (allowed: " + QuotaLimits.KEYS + ")");
                }
                if (!q.getValue().canConvertToLong() || !q.getValue().isIntegralNumber()
                        || q.getValue().asLong() < 0) {
                    throw new IllegalArgumentException(where + ".quotas." + q.getKey()
                            + ": must be a non-negative integer");
                }
                quotas.put(q.getKey(), q.getValue().asLong());
            });
        }

        return new Tenant(id, ImmutableList.copyOf(subnets), hosts.build(), edgePorts,
                accounts.build(), quotas.build());
    }

    private static <K> void claim(Map<K, String> owner, K key, String tenant, String what) {
        String prev = owner.putIfAbsent(key, tenant);
        if (prev != null) {
            throw new IllegalArgumentException(what + " " + key + " is claimed by tenant " + prev
                    + " and tenant " + tenant);
        }
    }

    private static void onlyFields(JsonNode n, Set<String> allowed, String where) {
        n.fieldNames().forEachRemaining(f -> {
            if (!allowed.contains(f)) {
                throw new IllegalArgumentException(where + ": unknown field \"" + f + "\" (allowed: " + allowed + ")");
            }
        });
    }

    private static JsonNode required(JsonNode n, String field, String where) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException(where + ": missing field \"" + field + "\"");
        }
        return v;
    }

    private static String text(JsonNode n, String where) {
        if (!n.isTextual() || n.asText().isEmpty()) {
            throw new IllegalArgumentException(where + ": must be a non-empty string");
        }
        return n.asText();
    }

    private static <T> List<T> stringList(JsonNode n, String where, Function<String, T> f) {
        if (!n.isArray()) {
            throw new IllegalArgumentException(where + ": must be an array of strings");
        }
        ImmutableList.Builder<T> b = ImmutableList.builder();
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (JsonNode e : n) {
            String ew = where + "[" + i++ + "]";
            String s = text(e, ew);
            if (!seen.add(s)) {
                throw new IllegalArgumentException(ew + ": duplicate value " + s);
            }
            b.add(parse(s, ew, f));
        }
        return b.build();
    }

    private static <T> T parse(String s, String where, Function<String, T> f) {
        try {
            return f.apply(s);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(where + ": invalid value \"" + s + "\": " + e.getMessage(), e);
        }
    }

    private static IpPrefix ipPrefix(String s) {
        if (!s.contains("/")) {
            throw new IllegalArgumentException("not a prefix (missing /len)");
        }
        return IpPrefix.valueOf(s);
    }

    private static ConnectPoint connectPoint(String s) {
        ConnectPoint cp = ConnectPoint.deviceConnectPoint(s);
        if (!cp.deviceId().uri().getScheme().equals("of")) {
            throw new IllegalArgumentException("expected of:<dpid>/<port>");
        }
        return cp;
    }
}
