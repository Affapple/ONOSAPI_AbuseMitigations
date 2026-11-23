package lu.uni.sdn.apigateway;

import lombok.Getter;
import lu.uni.sdn.apigateway.conflict.CompiledFlowMonitor;
import lu.uni.sdn.apigateway.quota.QuotaLimits;
import lu.uni.sdn.apigateway.quota.QuotaManager;
import lu.uni.sdn.apigateway.ratelimit.RateLimitSettings;
import lu.uni.sdn.apigateway.ratelimit.TokenBucketLimiter;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import lu.uni.sdn.apigateway.tenant.TenantsConfig;
import lu.uni.sdn.apigateway.throttle.ThrottleManager;
import lu.uni.sdn.apigateway.throttle.ThrottleSettings;
import lu.uni.sdn.apigateway.validation.ValidationLimits;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.basics.SubjectFactories;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.intent.IntentService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static lu.uni.sdn.apigateway.ModuleToggles.ACL_ENABLED;
import static lu.uni.sdn.apigateway.ModuleToggles.ACL_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.ModuleToggles.ADAPTIVE_ENABLED;
import static lu.uni.sdn.apigateway.ModuleToggles.ADAPTIVE_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.ModuleToggles.CONFLICT_ENABLED;
import static lu.uni.sdn.apigateway.ModuleToggles.CONFLICT_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.ModuleToggles.QUOTA_ENABLED;
import static lu.uni.sdn.apigateway.ModuleToggles.QUOTA_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.ModuleToggles.RATE_LIMIT_ENABLED;
import static lu.uni.sdn.apigateway.ModuleToggles.RATE_LIMIT_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.ModuleToggles.VALIDATION_ENABLED;
import static lu.uni.sdn.apigateway.ModuleToggles.VALIDATION_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.ProxySettings.PROXY_BASE_URL;
import static lu.uni.sdn.apigateway.ProxySettings.PROXY_BASE_URL_DEFAULT;
import static lu.uni.sdn.apigateway.ProxySettings.SERVICE_ACCOUNT_PASSWORD;
import static lu.uni.sdn.apigateway.ProxySettings.SERVICE_ACCOUNT_PASSWORD_DEFAULT;
import static lu.uni.sdn.apigateway.quota.QuotaLimits.CHURN_LIMIT;
import static lu.uni.sdn.apigateway.quota.QuotaLimits.CHURN_LIMIT_DEFAULT;
import static lu.uni.sdn.apigateway.quota.QuotaLimits.CHURN_WINDOW_SECONDS;
import static lu.uni.sdn.apigateway.quota.QuotaLimits.CHURN_WINDOW_SECONDS_DEFAULT;
import static lu.uni.sdn.apigateway.quota.QuotaLimits.MAX_FLOWS;
import static lu.uni.sdn.apigateway.quota.QuotaLimits.MAX_FLOWS_DEFAULT;
import static lu.uni.sdn.apigateway.quota.QuotaLimits.MAX_FLOWS_PER_DEVICE;
import static lu.uni.sdn.apigateway.quota.QuotaLimits.MAX_FLOWS_PER_DEVICE_DEFAULT;
import static lu.uni.sdn.apigateway.quota.QuotaLimits.MAX_INTENTS;
import static lu.uni.sdn.apigateway.quota.QuotaLimits.MAX_INTENTS_DEFAULT;
import static lu.uni.sdn.apigateway.ratelimit.RateLimitSettings.MAX_BATCH_SIZE;
import static lu.uni.sdn.apigateway.ratelimit.RateLimitSettings.MAX_BATCH_SIZE_DEFAULT;
import static lu.uni.sdn.apigateway.ratelimit.RateLimitSettings.MAX_BODY_BYTES;
import static lu.uni.sdn.apigateway.ratelimit.RateLimitSettings.MAX_BODY_BYTES_DEFAULT;
import static lu.uni.sdn.apigateway.ratelimit.RateLimitSettings.RATE_LIMIT_BURST;
import static lu.uni.sdn.apigateway.ratelimit.RateLimitSettings.RATE_LIMIT_BURST_DEFAULT;
import static lu.uni.sdn.apigateway.ratelimit.RateLimitSettings.RATE_LIMIT_RPS;
import static lu.uni.sdn.apigateway.ratelimit.RateLimitSettings.RATE_LIMIT_RPS_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.COOLDOWN;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.COOLDOWN_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.COOLDOWN_MAX;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.COOLDOWN_MAX_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.FIXED_EFFECT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.FIXED_EFFECT_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.FIXED_PENALTY;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.FIXED_PENALTY_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.FIXED_RATE_FACTOR;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.FIXED_RATE_FACTOR_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.LEVEL1_THRESHOLD;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.LEVEL1_THRESHOLD_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.LEVEL2_THRESHOLD;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.LEVEL2_THRESHOLD_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.LEVEL3_THRESHOLD;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.LEVEL3_THRESHOLD_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.RESTRICTED_RATE_FACTOR;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.RESTRICTED_RATE_FACTOR_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.SCORE_HALF_LIFE;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.SCORE_HALF_LIFE_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.THROTTLE_MODE;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.THROTTLE_MODE_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.WEIGHT_ACL;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.WEIGHT_ACL_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.WEIGHT_CONFLICT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.WEIGHT_CONFLICT_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.WEIGHT_QUOTA;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.WEIGHT_QUOTA_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.WEIGHT_RATE_LIMIT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.WEIGHT_RATE_LIMIT_DEFAULT;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.WEIGHT_VALIDATION;
import static lu.uni.sdn.apigateway.throttle.ThrottleSettings.WEIGHT_VALIDATION_DEFAULT;
import static lu.uni.sdn.apigateway.validation.ValidationLimits.MAX_PRIORITY;
import static lu.uni.sdn.apigateway.validation.ValidationLimits.MAX_PRIORITY_DEFAULT;
import static lu.uni.sdn.apigateway.validation.ValidationLimits.MAX_TIMEOUT;
import static lu.uni.sdn.apigateway.validation.ValidationLimits.MAX_TIMEOUT_DEFAULT;
import static lu.uni.sdn.apigateway.validation.ValidationLimits.MIN_PRIORITY;
import static lu.uni.sdn.apigateway.validation.ValidationLimits.MIN_PRIORITY_DEFAULT;

/**
 * The gateway's single ONOS component (one {@code cfg set} target switches C1/C2/C3
 * atomically): component config, lifecycle, tenant registry, and the one place
 * rejections are recorded. Each module parses its own settings ({@code XSettings.from});
 * the compiled-flow listener lives in conflict/.
 *
 * <p>The registry is pushed by the admin as
 * {@code POST /onos/v1/network/configuration/apps/lu.uni.sdn.api_gateway/tenants}
 * (scripts/vm-push-tenants.sh). Push it after the app is active; a push made
 * before activation is queued by ONOS and applied at activation. Until a valid
 * registry is present the gateway knows nobody and denies everything except
 * whoami (a WARN is logged).</p>
 */
@Component(
        immediate = true,
        service = GatewayService.class,
        property = {
                ACL_ENABLED + ":Boolean=" + ACL_ENABLED_DEFAULT,
                RATE_LIMIT_ENABLED + ":Boolean=" + RATE_LIMIT_ENABLED_DEFAULT,
                QUOTA_ENABLED + ":Boolean=" + QUOTA_ENABLED_DEFAULT,
                VALIDATION_ENABLED + ":Boolean=" + VALIDATION_ENABLED_DEFAULT,
                CONFLICT_ENABLED + ":Boolean=" + CONFLICT_ENABLED_DEFAULT,
                ADAPTIVE_ENABLED + ":Boolean=" + ADAPTIVE_ENABLED_DEFAULT,
                PROXY_BASE_URL + "=" + PROXY_BASE_URL_DEFAULT,
                SERVICE_ACCOUNT_PASSWORD + "=" + SERVICE_ACCOUNT_PASSWORD_DEFAULT,
                RATE_LIMIT_RPS + ":Double=" + RATE_LIMIT_RPS_DEFAULT,
                RATE_LIMIT_BURST + ":Integer=" + RATE_LIMIT_BURST_DEFAULT,
                MAX_BATCH_SIZE + ":Integer=" + MAX_BATCH_SIZE_DEFAULT,
                MAX_BODY_BYTES + ":Integer=" + MAX_BODY_BYTES_DEFAULT,
                MAX_FLOWS + ":Integer=" + MAX_FLOWS_DEFAULT,
                MAX_FLOWS_PER_DEVICE + ":Integer=" + MAX_FLOWS_PER_DEVICE_DEFAULT,
                MAX_INTENTS + ":Integer=" + MAX_INTENTS_DEFAULT,
                CHURN_LIMIT + ":Integer=" + CHURN_LIMIT_DEFAULT,
                CHURN_WINDOW_SECONDS + ":Integer=" + CHURN_WINDOW_SECONDS_DEFAULT,
                MIN_PRIORITY + ":Integer=" + MIN_PRIORITY_DEFAULT,
                MAX_PRIORITY + ":Integer=" + MAX_PRIORITY_DEFAULT,
                MAX_TIMEOUT + ":Integer=" + MAX_TIMEOUT_DEFAULT,
                THROTTLE_MODE + "=" + THROTTLE_MODE_DEFAULT,
                SCORE_HALF_LIFE + ":Double=" + SCORE_HALF_LIFE_DEFAULT,
                LEVEL1_THRESHOLD + ":Double=" + LEVEL1_THRESHOLD_DEFAULT,
                LEVEL2_THRESHOLD + ":Double=" + LEVEL2_THRESHOLD_DEFAULT,
                LEVEL3_THRESHOLD + ":Double=" + LEVEL3_THRESHOLD_DEFAULT,
                RESTRICTED_RATE_FACTOR + ":Double=" + RESTRICTED_RATE_FACTOR_DEFAULT,
                COOLDOWN + ":Integer=" + COOLDOWN_DEFAULT,
                COOLDOWN_MAX + ":Integer=" + COOLDOWN_MAX_DEFAULT,
                FIXED_PENALTY + ":Integer=" + FIXED_PENALTY_DEFAULT,
                FIXED_EFFECT + "=" + FIXED_EFFECT_DEFAULT,
                FIXED_RATE_FACTOR + ":Double=" + FIXED_RATE_FACTOR_DEFAULT,
                WEIGHT_RATE_LIMIT + ":Double=" + WEIGHT_RATE_LIMIT_DEFAULT,
                WEIGHT_QUOTA + ":Double=" + WEIGHT_QUOTA_DEFAULT,
                WEIGHT_VALIDATION + ":Double=" + WEIGHT_VALIDATION_DEFAULT,
                WEIGHT_CONFLICT + ":Double=" + WEIGHT_CONFLICT_DEFAULT,
                WEIGHT_ACL + ":Double=" + WEIGHT_ACL_DEFAULT,
        }
)
public class GatewayManager implements GatewayService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry netcfgRegistry;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;

    // --- Module settings (immutable snapshots, replaced on cfg set) and module state.
    private volatile @Getter ModuleToggles toggles = ModuleToggles.from(null);
    private volatile @Getter ProxySettings proxySettings = ProxySettings.from(null);
    private volatile @Getter RateLimitSettings rateLimitSettings = RateLimitSettings.from(null);
    private volatile @Getter QuotaLimits quotaLimits = QuotaLimits.from(null);
    private volatile @Getter ValidationLimits validationLimits = ValidationLimits.from(null);
    private volatile @Getter ThrottleSettings throttleSettings = ThrottleSettings.from(null);
    /** Registry + tenant app ids. */
    private volatile @Getter GatewaySnapshot snapshot = GatewaySnapshot.empty();

    private final @Getter TokenBucketLimiter limiter = new TokenBucketLimiter();
    private final @Getter ThrottleManager throttleManager = new ThrottleManager(System::nanoTime);
    private final @Getter RejectionCounters rejections = new RejectionCounters();
    private @Getter QuotaManager quotaManager;
    private CompiledFlowMonitor compiledFlowMonitor;
    private ApplicationId appId;

    // --- Tenant registry (netcfg).

    private final ConfigFactory<ApplicationId, TenantsConfig> tenantsFactory =
            new ConfigFactory<ApplicationId, TenantsConfig>(
                    SubjectFactories.APP_SUBJECT_FACTORY, TenantsConfig.class, TenantsConfig.KEY) {
                @Override
                public TenantsConfig createConfig() {
                    return new TenantsConfig();
                }
            };
    private final NetworkConfigListener netcfgListener = new InternalNetcfgListener();

    @Activate
    protected void activate(ComponentContext context) {
        appId = coreService.registerApplication(APP_NAME);
        quotaManager = new QuotaManager(flowRuleService, intentService, System::nanoTime);
        if (intentService != null && flowRuleService != null) {
            compiledFlowMonitor = new CompiledFlowMonitor(intentService, flowRuleService, this::getSnapshot,
                    () -> toggles.isConflictEnabled(), (t, reason, msg) -> recordRejection(t, reason, msg, false));
            compiledFlowMonitor.start();
        }
        cfgService.registerProperties(getClass());
        readComponentConfiguration(context);
        netcfgRegistry.addListener(netcfgListener);
        netcfgRegistry.registerConfigFactory(tenantsFactory);
        loadTenants("activate");
        log.info("Started {} (appId {})", APP_NAME, appId.id());
    }

    @Deactivate
    protected void deactivate() {
        if (compiledFlowMonitor != null) {
            compiledFlowMonitor.stop();
        }
        netcfgRegistry.removeListener(netcfgListener);
        netcfgRegistry.unregisterConfigFactory(tenantsFactory);
        cfgService.unregisterProperties(getClass(), false);
        snapshot = GatewaySnapshot.empty();
        log.info("Stopped");
    }

    @Modified
    protected void modified(ComponentContext context) {
        readComponentConfiguration(context);
    }

    /**
     * Lets every module parse its settings; reacts to conflictEnabled false -&gt; true
     * with one compiled-flow rescan (intents installed while it was off).
     *
     * @param context component context (null: defaults)
     */
    private void readComponentConfiguration(ComponentContext context) {
        Dictionary<?, ?> p = context == null ? null : context.getProperties();
        boolean conflictWasEnabled = toggles.isConflictEnabled();
        toggles = ModuleToggles.from(p);
        proxySettings = ProxySettings.from(p);
        rateLimitSettings = RateLimitSettings.from(p);
        quotaLimits = QuotaLimits.from(p);
        validationLimits = ValidationLimits.from(p);
        throttleSettings = ThrottleSettings.from(p);
        if (!conflictWasEnabled && toggles.isConflictEnabled() && compiledFlowMonitor != null) {
            compiledFlowMonitor.rescanAll();
        }
        log.info("Settings: {}; {}; {}; {}; {}; proxyBaseUrl={}", toggles, rateLimitSettings, quotaLimits,
                 validationLimits, throttleSettings, proxySettings.getBaseUrl());
    }

    @Override
    public void recordRejection(String tenantId, String reason, String message, boolean formatOnFlowWrite) {
        rejections.count(tenantId, reason, message);
        if (tenantId == null || !toggles.isAdaptiveEnabled()) {
            return;
        }
        ThrottleSettings ts = throttleSettings;
        boolean penaltyStarted = throttleManager.record(tenantId, reason,
                ThrottleManager.weightFor(reason, formatOnFlowWrite, ts), ts);
        if (penaltyStarted && !ts.isFixedBlockWrites()) {
            limiter.scale(TokenBucketLimiter.tenantKey(tenantId), ts.getFixedRateFactor());
        }
    }

    /**
     * (Re)loads the registry from network config and publishes a new snapshot.
     * <ul>
     *   <li>config absent: empty snapshot (fail closed), WARN;</li>
     *   <li>config invalid: previous snapshot kept, ERROR;</li>
     *   <li>config valid: app ids registered, then registry + app ids published
     *       together with one volatile write; WARN if it has no tenants.</li>
     * </ul>
     *
     * @param why event that caused the reload, for the log
     */
    synchronized void loadTenants(String why) {
        TenantsConfig cfg = netcfgRegistry.getConfig(appId, TenantsConfig.class);
        if (cfg == null) {
            snapshot = GatewaySnapshot.empty();
            log.warn("Tenant registry is EMPTY: nothing in netcfg apps/{}/{} ({}). The gateway knows no users "
                     + "and denies everything except whoami. Push it with scripts/vm-push-tenants.sh.",
                     APP_NAME, TenantsConfig.KEY, why);
            return;
        }
        TenantRegistry reg;
        try {
            reg = cfg.registry();
        } catch (IllegalArgumentException e) {
            log.error("Invalid tenant registry ({}), keeping previous one: {}", why, e.getMessage());
            return;
        }
        Map<String, ApplicationId> ids = new LinkedHashMap<>();
        for (Tenant t : reg.tenants().values()) {
            ids.put(t.getId(), coreService.registerApplication(t.appName()));
        }
        snapshot = new GatewaySnapshot(reg, ids);
        Set<String> tenants = reg.tenants().keySet();
        if (tenants.isEmpty()) {
            log.warn("Tenant registry loaded ({}) but has NO tenants; admins={} serviceAccount={}",
                     why, reg.admins(), reg.serviceAccount());
        } else {
            log.info("Loaded tenant registry ({}): tenants={} appIds={} admins={} serviceAccount={}",
                     why, tenants, ids, reg.admins(), reg.serviceAccount());
        }
    }

    private class InternalNetcfgListener implements NetworkConfigListener {
        @Override
        public boolean isRelevant(NetworkConfigEvent event) {
            return event.configClass() == TenantsConfig.class;
        }

        @Override
        public void event(NetworkConfigEvent event) {
            switch (event.type()) {
                case CONFIG_ADDED:
                case CONFIG_UPDATED:
                case CONFIG_REMOVED:
                    loadTenants(event.type().name());
                    break;
                default:
                    break;
            }
        }
    }
}
