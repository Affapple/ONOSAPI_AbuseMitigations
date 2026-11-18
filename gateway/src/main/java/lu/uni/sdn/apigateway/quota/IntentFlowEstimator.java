package lu.uni.sdn.apigateway.quota;

import com.google.common.collect.ImmutableMap;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.HostId;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.intent.HostToHostIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.PointToPointIntent;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Estimates the flows an intent will compile to, per device, BEFORE it is admitted
 * (B3: intents still compiling must count against the quotas).
 *
 * <p>Per-device counts measured live on ONOS 2.7 (this topology, see the B3-fix
 * report): a HostToHostIntent compiles to 2 flows (one per direction) on every
 * switch of the path, also when both hosts are on one switch; a PointToPointIntent
 * compiles to 1 flow on every switch of the path.</p>
 *
 * <p>Path: the devices of all shortest paths returned by the PathService between
 * the endpoint devices (union, so equal-cost alternatives are covered). If an
 * endpoint cannot be located or no path exists, the estimate is the per-device
 * count on every device the registry knows (conservative): such an intent would
 * fail now but ONOS recompiles it when the topology changes, so it is admitted
 * rather than rejected, without letting it slip past the quotas.</p>
 */
public final class IntentFlowEstimator {

    /** Measured: flows per path device for a HostToHostIntent. */
    public static final int H2H_FLOWS_PER_DEVICE = 2;
    /** Measured: flows per path device for a PointToPointIntent. */
    public static final int P2P_FLOWS_PER_DEVICE = 1;

    private IntentFlowEstimator() {
    }

    /**
     * Estimates compiled flows per device.
     *
     * @param intent         intent (H2H or P2P; other types get the conservative H2H estimate)
     * @param locateHost     host id to its attachment point, if known
     * @param paths          (src device, dst device) to shortest paths
     * @param allDevices     devices used for the conservative estimate
     * @return device to estimated flow count
     */
    public static Map<DeviceId, Integer> estimate(Intent intent,
                                                  Function<HostId, Optional<ConnectPoint>> locateHost,
                                                  BiFunction<DeviceId, DeviceId, Set<Path>> paths,
                                                  Collection<DeviceId> allDevices) {
        ConnectPoint a = null;
        ConnectPoint b = null;
        int perDevice = H2H_FLOWS_PER_DEVICE;
        if (intent instanceof HostToHostIntent) {
            HostToHostIntent h = (HostToHostIntent) intent;
            a = locateHost.apply(h.one()).orElse(null);
            b = locateHost.apply(h.two()).orElse(null);
        } else if (intent instanceof PointToPointIntent) {
            PointToPointIntent p = (PointToPointIntent) intent;
            a = p.filteredIngressPoint().connectPoint();
            b = p.filteredEgressPoint().connectPoint();
            perDevice = P2P_FLOWS_PER_DEVICE;
        }
        Set<DeviceId> devices = (a == null || b == null) ? null : pathDevices(a.deviceId(), b.deviceId(), paths);
        if (devices == null || devices.isEmpty()) {
            devices = new LinkedHashSet<>(allDevices);
        }
        ImmutableMap.Builder<DeviceId, Integer> m = ImmutableMap.builder();
        for (DeviceId d : devices) {
            m.put(d, perDevice);
        }
        return m.build();
    }

    private static Set<DeviceId> pathDevices(DeviceId src, DeviceId dst,
                                             BiFunction<DeviceId, DeviceId, Set<Path>> paths) {
        Set<DeviceId> out = new LinkedHashSet<>();
        if (src.equals(dst)) {
            out.add(src);
            return out;
        }
        Set<Path> ps = paths.apply(src, dst);
        if (ps == null) {
            return out;
        }
        for (Path p : ps) {
            for (Link l : p.links()) {
                out.add(l.src().deviceId());
                out.add(l.dst().deviceId());
            }
        }
        return out;
    }
}
