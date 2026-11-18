package lu.uni.sdn.apigateway.tenant;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import lombok.Value;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onosproject.net.ConnectPoint;

/**
 * One tenant from tenants.json. Immutable.
 */
@Value
public class Tenant {
    /** Tenant id, e.g. "t1". Used in the ApplicationId name lu.uni.sdn.gw.&lt;id&gt;. */
    String id;
    ImmutableList<IpPrefix> subnets;
    ImmutableList<TenantHost> hosts;
    ImmutableSet<ConnectPoint> edgePorts;
    /** Karaf user name to gateway role (OPERATOR or VIEWER). */
    ImmutableMap<String, GatewayRole> accounts;
    /** Per-tenant quota overrides; empty means "use the defaults in code". */
    ImmutableMap<String, Long> quotas;

    /**
     * Name of the ONOS application id that owns this tenant's flows and intents.
     *
     * @return e.g. "lu.uni.sdn.gw.t1"
     */
    public String appName() {
        return TenantRegistry.TENANT_APP_PREFIX + id;
    }

    /**
     * Whether the MAC address belongs to one of this tenant's hosts.
     *
     * @param mac MAC address
     * @return true if owned
     */
    public boolean ownsMac(MacAddress mac) {
        return hosts.stream().anyMatch(h -> h.getMac().equals(mac));
    }

    /**
     * Whether the IP address is inside one of this tenant's subnets.
     *
     * @param ip IP address
     * @return true if owned
     */
    public boolean ownsIp(IpAddress ip) {
        return subnets.stream().anyMatch(p -> p.contains(ip));
    }

    /**
     * Whether the connect point is one of this tenant's edge ports.
     *
     * @param cp connect point
     * @return true if owned
     */
    public boolean ownsEdgePort(ConnectPoint cp) {
        return edgePorts.contains(cp);
    }
}
