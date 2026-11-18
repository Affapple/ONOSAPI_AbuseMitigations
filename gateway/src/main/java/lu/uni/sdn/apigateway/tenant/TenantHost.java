package lu.uni.sdn.apigateway.tenant;

import lombok.Value;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onosproject.net.ConnectPoint;

/**
 * A host that belongs to a tenant (one entry of "hosts" in tenants.json).
 */
@Value
public class TenantHost {
    String name;
    MacAddress mac;
    IpAddress ip;
    ConnectPoint location;
}
