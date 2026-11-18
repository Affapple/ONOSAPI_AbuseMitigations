package lu.uni.sdn.apigateway.acl;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreServiceAdapter;
import org.onosproject.core.DefaultApplicationId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory CoreService: registerApplication hands out ids, getAppId looks them up.
 */
public class TestCoreService extends CoreServiceAdapter {

    private final Map<String, ApplicationId> apps = new HashMap<>();

    @Override
    public synchronized ApplicationId registerApplication(String name) {
        return apps.computeIfAbsent(name, n -> new DefaultApplicationId(100 + apps.size(), n));
    }

    @Override
    public synchronized ApplicationId getAppId(String name) {
        return apps.get(name);
    }

    @Override
    public synchronized Set<ApplicationId> getAppIds() {
        return new HashSet<>(apps.values());
    }

    /**
     * Names registered so far.
     *
     * @return names
     */
    public synchronized Set<String> names() {
        return new HashSet<>(apps.keySet());
    }
}
