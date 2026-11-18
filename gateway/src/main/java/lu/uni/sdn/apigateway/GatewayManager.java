package lu.uni.sdn.apigateway;

import com.google.common.base.Strings;
import lu.uni.sdn.apigateway.acl.Ownership.Violation;
import lu.uni.sdn.apigateway.audit.BypassAuditor;
import lu.uni.sdn.apigateway.audit.RejectionCounters;
import lu.uni.sdn.apigateway.conflict.ConflictDetector;
import lu.uni.sdn.apigateway.conflict.TenantRules;
import lu.uni.sdn.apigateway.quota.QuotaLimits;
import lu.uni.sdn.apigateway.quota.QuotaManager;
import lu.uni.sdn.apigateway.ratelimit.TokenBucketLimiter;
import lu.uni.sdn.apigateway.tenant.Tenant;
import lu.uni.sdn.apigateway.tenant.TenantRegistry;
import lu.uni.sdn.apigateway.tenant.TenantsConfig;
import lu.uni.sdn.apigateway.throttle.ThrottleManager;
import lu.uni.sdn.apigateway.throttle.ThrottleSettings;
import lu.uni.sdn.apigateway.validation.ValidationLimits;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.basics.SubjectFactories;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleEvent;
import org.onosproject.net.flow.FlowRuleListener;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.intent.FlowRuleIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentEvent;
import org.onosproject.net.intent.IntentListener;
import org.onosproject.net.intent.IntentState;
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

import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static lu.uni.sdn.apigateway.OsgiPropertyConstants.THROTTLE_MODE;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.THROTTLE_MODE_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.SCORE_HALF_LIFE;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.SCORE_HALF_LIFE_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.LEVEL1_THRESHOLD;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.LEVEL1_THRESHOLD_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.LEVEL2_THRESHOLD;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.LEVEL2_THRESHOLD_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.LEVEL3_THRESHOLD;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.LEVEL3_THRESHOLD_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.RESTRICTED_RATE_FACTOR;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.RESTRICTED_RATE_FACTOR_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.COOLDOWN;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.COOLDOWN_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.COOLDOWN_MAX;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.COOLDOWN_MAX_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.FIXED_PENALTY;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.FIXED_PENALTY_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.WEIGHT_RATE_LIMIT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.WEIGHT_RATE_LIMIT_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.WEIGHT_QUOTA;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.WEIGHT_QUOTA_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.WEIGHT_VALIDATION;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.WEIGHT_VALIDATION_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.WEIGHT_CONFLICT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.WEIGHT_CONFLICT_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.WEIGHT_ACL;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.WEIGHT_ACL_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.FIXED_EFFECT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.FIXED_EFFECT_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.FIXED_RATE_FACTOR;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.FIXED_RATE_FACTOR_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.AUDIT_ENABLED;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.AUDIT_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.AUDIT_ALLOWED_APPS;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.AUDIT_ALLOWED_APPS_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.ACL_ENABLED;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.ACL_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.ADAPTIVE_ENABLED;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.ADAPTIVE_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.CHURN_LIMIT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.CHURN_LIMIT_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.CHURN_WINDOW_SECONDS;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.CHURN_WINDOW_SECONDS_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.CONFLICT_ENABLED;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.CONFLICT_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_BATCH_SIZE;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_BATCH_SIZE_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_BODY_BYTES;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_BODY_BYTES_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_FLOWS_PER_TENANT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_FLOWS_PER_TENANT_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_FLOWS_PER_TENANT_PER_DEVICE;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_FLOWS_PER_TENANT_PER_DEVICE_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_INTENTS_PER_TENANT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_INTENTS_PER_TENANT_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_PRIORITY;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_PRIORITY_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_TIMEOUT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MAX_TIMEOUT_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MIN_PRIORITY;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.MIN_PRIORITY_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.PROXY_BASE_URL;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.PROXY_BASE_URL_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.QUOTA_ENABLED;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.QUOTA_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.RATE_LIMIT_ENABLED;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.RATE_LIMIT_ENABLED_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.RATE_LIMIT_BURST;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.RATE_LIMIT_BURST_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.RATE_LIMIT_RPS;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.RATE_LIMIT_RPS_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.SERVICE_ACCOUNT_PASSWORD;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.SERVICE_ACCOUNT_PASSWORD_DEFAULT;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.VALIDATION_ENABLED;
import static lu.uni.sdn.apigateway.OsgiPropertyConstants.VALIDATION_ENABLED_DEFAULT;

/**
 * Gateway lifecycle: component config (module toggles, proxy settings), tenant
 * registry loaded from ONOS network config, and one ApplicationId per tenant.
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
                AUDIT_ENABLED + ":Boolean=" + AUDIT_ENABLED_DEFAULT,
                AUDIT_ALLOWED_APPS + "=" + AUDIT_ALLOWED_APPS_DEFAULT,
                PROXY_BASE_URL + "=" + PROXY_BASE_URL_DEFAULT,
                SERVICE_ACCOUNT_PASSWORD + "=" + SERVICE_ACCOUNT_PASSWORD_DEFAULT,
                RATE_LIMIT_RPS + ":Double=" + RATE_LIMIT_RPS_DEFAULT,
                RATE_LIMIT_BURST + ":Integer=" + RATE_LIMIT_BURST_DEFAULT,
                MAX_BATCH_SIZE + ":Integer=" + MAX_BATCH_SIZE_DEFAULT,
                MAX_BODY_BYTES + ":Integer=" + MAX_BODY_BYTES_DEFAULT,
                MAX_FLOWS_PER_TENANT + ":Integer=" + MAX_FLOWS_PER_TENANT_DEFAULT,
                MAX_FLOWS_PER_TENANT_PER_DEVICE + ":Integer=" + MAX_FLOWS_PER_TENANT_PER_DEVICE_DEFAULT,
                MAX_INTENTS_PER_TENANT + ":Integer=" + MAX_INTENTS_PER_TENANT_DEFAULT,
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

    /** Layer 1 role/path ACL and ownership checks (B1). */
    private boolean aclEnabled = ACL_ENABLED_DEFAULT;

    /** Per-tenant token-bucket rate limit (B2). */
    private boolean rateLimitEnabled = RATE_LIMIT_ENABLED_DEFAULT;

    /** Per-tenant flow/intent quotas and churn limit (B3). */
    private boolean quotaEnabled = QUOTA_ENABLED_DEFAULT;

    /** Payload format and semantic validation (B4). */
    private boolean validationEnabled = VALIDATION_ENABLED_DEFAULT;

    /** Cross-tenant conflict and spoofing detection (B5). */
    private boolean conflictEnabled = CONFLICT_ENABLED_DEFAULT;

    /** Adaptive throttling based on a decaying violation score (B6). */
    private boolean adaptiveEnabled = ADAPTIVE_ENABLED_DEFAULT;

    /** Bypass auditor: flag flows/intents that did not come through the gateway (B7). */
    private boolean auditEnabled = AUDIT_ENABLED_DEFAULT;

    /** Comma-separated non-tenant appIds whose flows/intents are legitimate (B7). */
    private String auditAllowedApps = AUDIT_ALLOWED_APPS_DEFAULT;

    /** Base URL of the ONOS REST API that /gw/v1 forwards reads to. */
    private String proxyBaseUrl = PROXY_BASE_URL_DEFAULT;

    /** Password of the viewer-only read-forwarding account (user name from tenants.json). */
    private String serviceAccountPassword = SERVICE_ACCOUNT_PASSWORD_DEFAULT;

    /** Token refill rate per tenant in requests (tokens) per second (B2). */
    private double rateLimitRps = RATE_LIMIT_RPS_DEFAULT;

    /** Token bucket capacity per tenant (B2). */
    private int rateLimitBurst = RATE_LIMIT_BURST_DEFAULT;

    /** Max flows per POST/DELETE /gw/flows batch, checked before decoding (B2). */
    private int maxBatchSize = MAX_BATCH_SIZE_DEFAULT;

    /** Max request body size in bytes, checked before parsing (B2). */
    private int maxBodyBytes = MAX_BODY_BYTES_DEFAULT;

    /** Default max flows per tenant (direct + intent-compiled) (B3). */
    private int maxFlowsPerTenant = MAX_FLOWS_PER_TENANT_DEFAULT;

    /** Default max flows per tenant on one device (B3). */
    private int maxFlowsPerTenantPerDevice = MAX_FLOWS_PER_TENANT_PER_DEVICE_DEFAULT;

    /** Default max intents per tenant (B3). */
    private int maxIntentsPerTenant = MAX_INTENTS_PER_TENANT_DEFAULT;

    /** Default max flow/intent add+delete operations per churn window (B3). */
    private int churnLimit = CHURN_LIMIT_DEFAULT;

    /** Length of the sliding churn window in seconds (B3). */
    private int churnWindowSeconds = CHURN_WINDOW_SECONDS_DEFAULT;

    /** Lowest priority of a tenant flow/intent (B4). */
    private int minPriority = MIN_PRIORITY_DEFAULT;

    /** Highest priority of a tenant flow/intent; above it the core apps' range (B4). */
    private int maxPriority = MAX_PRIORITY_DEFAULT;

    /** Longest flow timeout in seconds; permanent flows are never allowed (B4). */
    private int maxTimeout = MAX_TIMEOUT_DEFAULT;

    /** B6 policy: adaptive (levels) or fixed (Lab2: any violation blocks writes for fixedPenaltySeconds). */
    private String throttleMode = THROTTLE_MODE_DEFAULT;

    /** Half-life of the violation score in seconds (B6). */
    private double scoreHalfLife = SCORE_HALF_LIFE_DEFAULT;

    /** Score at which a tenant becomes Restricted (B6 level 1). */
    private double level1Threshold = LEVEL1_THRESHOLD_DEFAULT;

    /** Score at which a tenant enters Cooldown (B6 level 2). */
    private double level2Threshold = LEVEL2_THRESHOLD_DEFAULT;

    /** Score at which a tenant is Blocked (B6 level 3). */
    private double level3Threshold = LEVEL3_THRESHOLD_DEFAULT;

    /** Refill-rate factor of the B2 bucket at level 1 and above (B6). */
    private double restrictedRateFactor = RESTRICTED_RATE_FACTOR_DEFAULT;

    /** Cooldown (writes 429) in seconds on entering level 2; doubles on re-entry (B6). */
    private int cooldown = COOLDOWN_DEFAULT;

    /** Cap of the doubling cooldown in seconds (B6). */
    private int cooldownMax = COOLDOWN_MAX_DEFAULT;

    /** Fixed-mode write block in seconds after any violation (B6, Lab2 comparison). */
    private int fixedPenalty = FIXED_PENALTY_DEFAULT;

    /** Score added per rate-limit rejection (B6). */
    private double weightRateLimit = WEIGHT_RATE_LIMIT_DEFAULT;

    /** Score added per quota rejection (B6). */
    private double weightQuota = WEIGHT_QUOTA_DEFAULT;

    /** Score added per validation rejection (B6). */
    private double weightValidation = WEIGHT_VALIDATION_DEFAULT;

    /** Score added per conflict/spoofing rejection (B6). */
    private double weightConflict = WEIGHT_CONFLICT_DEFAULT;

    /** Score added per ACL/ownership rejection (B6). */
    private double weightAcl = WEIGHT_ACL_DEFAULT;

    /** Fixed-mode effect: rate (refill rate x fixedRateFactor on all requests) or block-writes (B6). */
    private String fixedEffect = FIXED_EFFECT_DEFAULT;

    /** Refill-rate factor during a fixed-mode penalty with fixedEffect=rate (B6). */
    private double fixedRateFactor = FIXED_RATE_FACTOR_DEFAULT;

    private volatile ThrottleSettings throttleSettings = currentThrottleSettings();
    private final ThrottleManager throttleManager = new ThrottleManager(System::nanoTime);
    private volatile ValidationLimits validationLimits =
            new ValidationLimits(minPriority, maxPriority, maxTimeout);
    private final TokenBucketLimiter limiter = new TokenBucketLimiter();
    private volatile QuotaLimits quotaLimits = currentQuotaLimits();
    private QuotaManager quotaManager;
    private volatile Limits limits = new Limits(rateLimitRps, rateLimitBurst, maxBatchSize, maxBodyBytes);
    private volatile ModuleToggles toggles = currentToggles();
    private volatile ProxySettings proxySettings = new ProxySettings(proxyBaseUrl, serviceAccountPassword);
    /** Registry + tenant app ids; replaced with one volatile write. */
    private volatile GatewaySnapshot snapshot = GatewaySnapshot.empty();

    private ApplicationId appId;

    private final ConfigFactory<ApplicationId, TenantsConfig> tenantsFactory =
            new ConfigFactory<ApplicationId, TenantsConfig>(
                    SubjectFactories.APP_SUBJECT_FACTORY, TenantsConfig.class, TenantsConfig.KEY) {
                @Override
                public TenantsConfig createConfig() {
                    return new TenantsConfig();
                }
            };

    private final NetworkConfigListener netcfgListener = new InternalNetcfgListener();
    private final IntentListener compiledFlowMonitor = new CompiledFlowMonitor();
    private final Map<String, Map<String, AtomicInteger>> conflicts = new ConcurrentHashMap<>();
    private final Map<String, String> lastConflicts = new ConcurrentHashMap<>();
    private ExecutorService conflictExecutor;
    private final BypassAuditor auditor = new BypassAuditor();
    private final RejectionCounters rejections = new RejectionCounters();
    private volatile Set<String> auditAllowed = BypassAuditor.parseAllowlist(AUDIT_ALLOWED_APPS_DEFAULT);
    private final FlowRuleListener auditFlowListener = new AuditFlowListener();
    private final IntentListener auditIntentListener = new AuditIntentListener();
    private ScheduledExecutorService auditExecutor;
    private final AtomicInteger auditPending = new AtomicInteger();
    /** Bound of pending audit checks; beyond it checks are dropped with a WARN. */
    static final int AUDIT_QUEUE = 10_000;
    /** Delay before checking an intent-compiled flow (its intent's installables may lag the event). */
    static final long AUDIT_INTENT_FLOW_DELAY_MS = 2000;
    /** Bound of the compiled-flow check queue (see activate). */
    static final int CONFLICT_QUEUE = 1000;

    @Activate
    protected void activate(ComponentContext context) {
        appId = coreService.registerApplication(APP_NAME);
        quotaManager = new QuotaManager(flowRuleService, intentService, System::nanoTime);
        // One worker, at most CONFLICT_QUEUE pending checks; on overflow the check is dropped
        // with a WARN (the next INSTALLED event or a conflictEnabled false->true rescan
        // covers it again).
        conflictExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(CONFLICT_QUEUE), Tools.groupedThreads("onos/gw", "conflict-%d", log),
                (task, ex) -> log.warn("Compiled-flow check queue full ({}), check dropped", CONFLICT_QUEUE));
        auditExecutor = Executors.newSingleThreadScheduledExecutor(Tools.groupedThreads("onos/gw", "audit-%d", log));
        if (intentService != null) {
            intentService.addListener(compiledFlowMonitor);
            intentService.addListener(auditIntentListener);
        }
        if (flowRuleService != null) {
            flowRuleService.addListener(auditFlowListener);
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
        if (intentService != null) {
            intentService.removeListener(compiledFlowMonitor);
            intentService.removeListener(auditIntentListener);
        }
        if (flowRuleService != null) {
            flowRuleService.removeListener(auditFlowListener);
        }
        if (auditExecutor != null) {
            auditExecutor.shutdownNow();
        }
        if (conflictExecutor != null) {
            conflictExecutor.shutdownNow();
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

    @Override
    public GatewaySnapshot snapshot() {
        return snapshot;
    }

    @Override
    public ModuleToggles toggles() {
        return toggles;
    }

    @Override
    public ProxySettings proxySettings() {
        return proxySettings;
    }

    @Override
    public Limits limits() {
        return limits;
    }

    @Override
    public TokenBucketLimiter rateLimiter() {
        return limiter;
    }

    @Override
    public QuotaLimits quotaLimits() {
        return quotaLimits;
    }

    @Override
    public QuotaManager quotaManager() {
        return quotaManager;
    }

    @Override
    public ValidationLimits validationLimits() {
        return validationLimits;
    }

    @Override
    public BypassAuditor auditor() {
        return auditor;
    }

    @Override
    public RejectionCounters rejections() {
        return rejections;
    }

    @Override
    public Set<String> auditAllowedApps() {
        return auditAllowed;
    }

    /**
     * B7: flow rules that appear (add requested / added) are classified on the audit
     * executor; intent-compiled ones after a short delay.
     */
    private class AuditFlowListener implements FlowRuleListener {
        @Override
        public boolean isRelevant(FlowRuleEvent event) {
            return toggles.isAuditEnabled() && (event.type() == FlowRuleEvent.Type.RULE_ADD_REQUESTED
                    || event.type() == FlowRuleEvent.Type.RULE_ADDED);
        }

        @Override
        public void event(FlowRuleEvent event) {
            FlowRule r = event.subject();
            ApplicationId a = coreService.getAppId(r.appId());
            String name = a == null ? "#" + r.appId() : a.name();
            long delay = BypassAuditor.INTENT_APP.equals(name) ? AUDIT_INTENT_FLOW_DELAY_MS : 0;
            submitAudit(() -> auditFlow(r, name), delay);
        }
    }

    /** B7: intents are classified when their install is requested. */
    private class AuditIntentListener implements IntentListener {
        @Override
        public boolean isRelevant(IntentEvent event) {
            return toggles.isAuditEnabled() && event.type() == IntentEvent.Type.INSTALL_REQ;
        }

        @Override
        public void event(IntentEvent event) {
            Intent i = event.subject();
            submitAudit(() -> auditIntent(i), 0);
        }
    }

    private void submitAudit(Runnable task, long delayMs) {
        if (auditPending.incrementAndGet() > AUDIT_QUEUE) {
            auditPending.decrementAndGet();
            log.warn("Audit queue full ({}), check dropped", AUDIT_QUEUE);
            return;
        }
        auditExecutor.schedule(() -> {
            auditPending.decrementAndGet();
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("Audit check failed", e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void auditFlow(FlowRule r, String appName) {
        GatewaySnapshot snap = snapshot;
        Optional<String> why = auditor.classifyFlow(r.id().value(), appName, n -> isTenantApp(snap, n),
                id -> tenantIntentFlowIds(snap).contains(id), auditAllowed);
        if (why.isPresent() && auditor.report(new BypassAuditor.Bypass(System.currentTimeMillis(), "flow",
                r.deviceId().toString(), appName, Long.toString(r.id().value()), why.get()))) {
            log.warn("AUDIT bypass: flow {} on {} appId {}: {}", r.id().value(), r.deviceId(), appName, why.get());
        }
    }

    private void auditIntent(Intent i) {
        GatewaySnapshot snap = snapshot;
        String name = i.appId().name();
        Optional<String> why = auditor.classifyIntent(i.id().fingerprint(), name, n -> isTenantApp(snap, n),
                auditAllowed);
        if (why.isPresent() && auditor.report(new BypassAuditor.Bypass(System.currentTimeMillis(), "intent", "-",
                name, i.key().toString(), why.get()))) {
            log.warn("AUDIT bypass: intent {} appId {}: {}", i.key(), name, why.get());
        }
    }

    private static boolean isTenantApp(GatewaySnapshot snap, String name) {
        return snap.appIds().values().stream().anyMatch(a -> a.name().equals(name));
    }

    /** FlowIds of the compiled rules of all tenant intents. */
    private Set<Long> tenantIntentFlowIds(GatewaySnapshot snap) {
        Set<Long> ids = new HashSet<>();
        for (ApplicationId app : snap.appIds().values()) {
            for (Intent i : intentService.getIntentsByAppId(app)) {
                for (Intent inst : intentService.getInstallableIntents(i.key())) {
                    if (inst instanceof FlowRuleIntent) {
                        ((FlowRuleIntent) inst).flowRules().forEach(f -> ids.add(f.id().value()));
                    }
                }
            }
        }
        return ids;
    }

    @Override
    public ThrottleManager throttleManager() {
        return throttleManager;
    }

    @Override
    public ThrottleSettings throttleSettings() {
        return throttleSettings;
    }

    @Override
    public void recordViolation(String tenantId, String reason, boolean formatOnFlowWrite) {
        if (tenantId != null && toggles.isAdaptiveEnabled()) {
            ThrottleSettings ts = throttleSettings;
            throttleManager.record(tenantId, reason, ThrottleManager.weightFor(reason, formatOnFlowWrite, ts), ts);
        }
    }

    @Override
    public void recordConflict(String tenantId, String reason, String message) {
        conflicts.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
        lastConflicts.put(tenantId, reason + ": " + message);
    }

    @Override
    public Map<String, Integer> conflicts(String tenantId) {
        Map<String, Integer> m = new TreeMap<>();
        conflicts.getOrDefault(tenantId, Collections.emptyMap()).forEach((k, v) -> m.put(k, v.get()));
        return m;
    }

    @Override
    public String lastConflict(String tenantId) {
        return lastConflicts.get(tenantId);
    }

    /**
     * B5 compiled-flow check (detective, defence in depth): when a tenant's intent is
     * INSTALLED, its compiled flows are compared with the other tenants' rules on the
     * same devices (ConflictDetector.checkOverlap + destination capture). On a conflict
     * the intent is withdrawn, a compiled_conflict violation is recorded and logged.
     */
    private class CompiledFlowMonitor implements IntentListener {
        @Override
        public boolean isRelevant(IntentEvent event) {
            return event.type() == IntentEvent.Type.INSTALLED && toggles.isConflictEnabled()
                    && snapshot.tenantOfApp(event.subject().appId()).isPresent();
        }

        @Override
        public void event(IntentEvent event) {
            Intent intent = event.subject();
            GatewaySnapshot snap = snapshot;
            conflictExecutor.execute(() -> checkCompiled(intent, snap));
        }
    }

    /**
     * conflictEnabled went false -> true: intents installed while the check was off
     * never got an INSTALLED event with the check on, so re-check every INSTALLED
     * tenant intent once.
     */
    private void rescanAllIntents() {
        GatewaySnapshot snap = snapshot;
        int n = 0;
        for (ApplicationId app : snap.appIds().values()) {
            for (Intent i : intentService.getIntentsByAppId(app)) {
                if (intentService.getIntentState(i.key()) == IntentState.INSTALLED) {
                    conflictExecutor.execute(() -> checkCompiled(i, snap));
                    n++;
                }
            }
        }
        log.info("conflictEnabled turned on: re-checking {} installed tenant intents", n);
    }

    private void checkCompiled(Intent intent, GatewaySnapshot snap) {
        try {
            Optional<Tenant> owner = snap.tenantOfApp(intent.appId());
            if (!owner.isPresent()) {
                return;
            }
            Tenant t = owner.get();
            Map<DeviceId, List<ConflictDetector.OwnedRule>> existing =
                    TenantRules.collect(snap, flowRuleService, intentService);
            for (Intent inst : intentService.getInstallableIntents(intent.key())) {
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
                        // Only withdraw the very intent that was checked: a concurrent
                        // resubmission under the same key gets a new IntentId.
                        Intent current = intentService.getIntent(intent.key());
                        if (current == null || !current.id().equals(intent.id())) {
                            log.info("Intent {} changed while being checked; not withdrawing", intent.key());
                            return;
                        }
                        log.warn("Withdrawing intent {} of tenant {}: compiled flow conflicts: {}",
                                 intent.key(), t.getId(), v.get().getMessage());
                        recordConflict(t.getId(), ConflictDetector.COMPILED_CONFLICT,
                                       "intent " + intent.key() + ": " + v.get().getMessage());
                        recordViolation(t.getId(), ConflictDetector.COMPILED_CONFLICT, false);
                        rejections.count(t.getId(), ConflictDetector.COMPILED_CONFLICT);
                        intentService.withdraw(intent);
                        return;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Compiled-flow check of intent {} failed", intent.key(), e);
        }
    }

    private void readComponentConfiguration(ComponentContext context) {
        Dictionary<?, ?> p = context == null || context.getProperties() == null
                ? new Hashtable<>() : context.getProperties();
        aclEnabled = Tools.isPropertyEnabled(p, ACL_ENABLED, ACL_ENABLED_DEFAULT);
        rateLimitEnabled = Tools.isPropertyEnabled(p, RATE_LIMIT_ENABLED, RATE_LIMIT_ENABLED_DEFAULT);
        quotaEnabled = Tools.isPropertyEnabled(p, QUOTA_ENABLED, QUOTA_ENABLED_DEFAULT);
        validationEnabled = Tools.isPropertyEnabled(p, VALIDATION_ENABLED, VALIDATION_ENABLED_DEFAULT);
        String mode = Strings.emptyToNull(Tools.get(p, THROTTLE_MODE));
        if (mode != null && !mode.trim().equals(ThrottleSettings.ADAPTIVE)
                && !mode.trim().equals(ThrottleSettings.FIXED)) {
            log.warn("Invalid {}={}, using {}", THROTTLE_MODE, mode, THROTTLE_MODE_DEFAULT);
            mode = null;
        }
        throttleMode = mode == null ? THROTTLE_MODE_DEFAULT : mode.trim();
        scoreHalfLife = positiveDouble(p, SCORE_HALF_LIFE, SCORE_HALF_LIFE_DEFAULT);
        double l1 = positiveDouble(p, LEVEL1_THRESHOLD, LEVEL1_THRESHOLD_DEFAULT);
        double l2 = positiveDouble(p, LEVEL2_THRESHOLD, LEVEL2_THRESHOLD_DEFAULT);
        double l3 = positiveDouble(p, LEVEL3_THRESHOLD, LEVEL3_THRESHOLD_DEFAULT);
        if (!(l1 < l2 && l2 < l3)) {
            log.warn("Throttle thresholds must increase ({} < {} < {}); using defaults", l1, l2, l3);
            l1 = LEVEL1_THRESHOLD_DEFAULT;
            l2 = LEVEL2_THRESHOLD_DEFAULT;
            l3 = LEVEL3_THRESHOLD_DEFAULT;
        }
        level1Threshold = l1;
        level2Threshold = l2;
        level3Threshold = l3;
        restrictedRateFactor = positiveDouble(p, RESTRICTED_RATE_FACTOR, RESTRICTED_RATE_FACTOR_DEFAULT);
        cooldown = positiveInt(p, COOLDOWN, COOLDOWN_DEFAULT);
        cooldownMax = Math.max(cooldown, positiveInt(p, COOLDOWN_MAX, COOLDOWN_MAX_DEFAULT));
        fixedPenalty = positiveInt(p, FIXED_PENALTY, FIXED_PENALTY_DEFAULT);
        String effect = Strings.emptyToNull(Tools.get(p, FIXED_EFFECT));
        if (effect != null && !effect.trim().equals(ThrottleSettings.EFFECT_RATE)
                && !effect.trim().equals(ThrottleSettings.EFFECT_BLOCK_WRITES)) {
            log.warn("Invalid {}={}, using {}", FIXED_EFFECT, effect, FIXED_EFFECT_DEFAULT);
            effect = null;
        }
        fixedEffect = effect == null ? FIXED_EFFECT_DEFAULT : effect.trim();
        fixedRateFactor = positiveDouble(p, FIXED_RATE_FACTOR, FIXED_RATE_FACTOR_DEFAULT);
        weightRateLimit = nonNegativeDouble(p, WEIGHT_RATE_LIMIT, WEIGHT_RATE_LIMIT_DEFAULT);
        weightQuota = nonNegativeDouble(p, WEIGHT_QUOTA, WEIGHT_QUOTA_DEFAULT);
        weightValidation = nonNegativeDouble(p, WEIGHT_VALIDATION, WEIGHT_VALIDATION_DEFAULT);
        weightConflict = nonNegativeDouble(p, WEIGHT_CONFLICT, WEIGHT_CONFLICT_DEFAULT);
        weightAcl = nonNegativeDouble(p, WEIGHT_ACL, WEIGHT_ACL_DEFAULT);
        throttleSettings = currentThrottleSettings();
        boolean conflictWasEnabled = conflictEnabled;
        conflictEnabled = Tools.isPropertyEnabled(p, CONFLICT_ENABLED, CONFLICT_ENABLED_DEFAULT);
        boolean rescanCompiled = !conflictWasEnabled && conflictEnabled;
        adaptiveEnabled = Tools.isPropertyEnabled(p, ADAPTIVE_ENABLED, ADAPTIVE_ENABLED_DEFAULT);
        auditEnabled = Tools.isPropertyEnabled(p, AUDIT_ENABLED, AUDIT_ENABLED_DEFAULT);
        String allow = Tools.get(p, AUDIT_ALLOWED_APPS);
        auditAllowedApps = allow == null ? AUDIT_ALLOWED_APPS_DEFAULT : allow;
        auditAllowed = BypassAuditor.parseAllowlist(auditAllowedApps);
        String url = Strings.emptyToNull(Tools.get(p, PROXY_BASE_URL));
        proxyBaseUrl = url == null ? PROXY_BASE_URL_DEFAULT : (url.endsWith("/") ? url : url + "/");
        String pw = Tools.get(p, SERVICE_ACCOUNT_PASSWORD);
        serviceAccountPassword = pw == null ? SERVICE_ACCOUNT_PASSWORD_DEFAULT : pw;
        rateLimitRps = positiveDouble(p, RATE_LIMIT_RPS, RATE_LIMIT_RPS_DEFAULT);
        rateLimitBurst = positiveInt(p, RATE_LIMIT_BURST, RATE_LIMIT_BURST_DEFAULT);
        maxBatchSize = positiveInt(p, MAX_BATCH_SIZE, MAX_BATCH_SIZE_DEFAULT);
        maxBodyBytes = positiveInt(p, MAX_BODY_BYTES, MAX_BODY_BYTES_DEFAULT);
        maxFlowsPerTenant = positiveInt(p, MAX_FLOWS_PER_TENANT, MAX_FLOWS_PER_TENANT_DEFAULT);
        maxFlowsPerTenantPerDevice = positiveInt(p, MAX_FLOWS_PER_TENANT_PER_DEVICE,
                                                 MAX_FLOWS_PER_TENANT_PER_DEVICE_DEFAULT);
        maxIntentsPerTenant = positiveInt(p, MAX_INTENTS_PER_TENANT, MAX_INTENTS_PER_TENANT_DEFAULT);
        churnLimit = positiveInt(p, CHURN_LIMIT, CHURN_LIMIT_DEFAULT);
        churnWindowSeconds = positiveInt(p, CHURN_WINDOW_SECONDS, CHURN_WINDOW_SECONDS_DEFAULT);
        int minP = positiveInt(p, MIN_PRIORITY, MIN_PRIORITY_DEFAULT);
        int maxP = positiveInt(p, MAX_PRIORITY, MAX_PRIORITY_DEFAULT);
        if (minP > maxP) {
            log.warn("minPriority {} > maxPriority {}; using defaults", minP, maxP);
            minP = MIN_PRIORITY_DEFAULT;
            maxP = MAX_PRIORITY_DEFAULT;
        }
        minPriority = minP;
        maxPriority = maxP;
        maxTimeout = positiveInt(p, MAX_TIMEOUT, MAX_TIMEOUT_DEFAULT);
        validationLimits = new ValidationLimits(minPriority, maxPriority, maxTimeout);
        toggles = currentToggles();
        if (rescanCompiled && conflictExecutor != null) {
            rescanAllIntents();
        }
        proxySettings = new ProxySettings(proxyBaseUrl, serviceAccountPassword);
        limits = new Limits(rateLimitRps, rateLimitBurst, maxBatchSize, maxBodyBytes);
        quotaLimits = currentQuotaLimits();
        log.info("Module toggles: {}; {}; {}; {}; {}; proxyBaseUrl={}", toggles, limits, quotaLimits,
                 validationLimits, throttleSettings, proxyBaseUrl);
    }

    /** Reads a property that must be &gt; 0; invalid or missing values fall back to the default. */
    private double positiveDouble(Dictionary<?, ?> p, String name, double def) {
        String v = Tools.get(p, name);
        try {
            double d = v == null ? def : Double.parseDouble(v.trim());
            if (d > 0 && !Double.isInfinite(d)) {
                return d;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        log.warn("Invalid {}={}, using {}", name, v, def);
        return def;
    }

    /** Reads a property that must be &gt;= 0; invalid or missing values fall back to the default. */
    private double nonNegativeDouble(Dictionary<?, ?> p, String name, double def) {
        String v = Tools.get(p, name);
        try {
            double d = v == null ? def : Double.parseDouble(v.trim());
            if (d >= 0 && !Double.isInfinite(d)) {
                return d;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        log.warn("Invalid {}={}, using {}", name, v, def);
        return def;
    }

    private ThrottleSettings currentThrottleSettings() {
        return new ThrottleSettings(throttleMode, scoreHalfLife, level1Threshold, level2Threshold, level3Threshold,
                restrictedRateFactor, cooldown, cooldownMax, fixedPenalty, fixedEffect, fixedRateFactor,
                weightRateLimit, weightQuota,
                weightValidation, weightConflict, weightAcl);
    }

    /** Reads an integer property that must be &gt; 0; invalid or missing values fall back to the default. */
    private int positiveInt(Dictionary<?, ?> p, String name, int def) {
        String v = Tools.get(p, name);
        try {
            int i = v == null ? def : Integer.parseInt(v.trim());
            if (i > 0) {
                return i;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        log.warn("Invalid {}={}, using {}", name, v, def);
        return def;
    }

    private QuotaLimits currentQuotaLimits() {
        return new QuotaLimits(maxFlowsPerTenant, maxFlowsPerTenantPerDevice, maxIntentsPerTenant,
                               churnLimit, churnWindowSeconds);
    }

    private ModuleToggles currentToggles() {
        return new ModuleToggles(aclEnabled, rateLimitEnabled, quotaEnabled,
                validationEnabled, conflictEnabled, adaptiveEnabled, auditEnabled);
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
        if (reg.tenants().isEmpty()) {
            log.warn("Tenant registry loaded ({}) but has NO tenants; admins={} serviceAccount={}",
                     why, reg.admins(), reg.serviceAccount());
        } else {
            log.info("Loaded tenant registry ({}): tenants={} appIds={} admins={} serviceAccount={}",
                     why, reg.tenants().keySet(), ids, reg.admins(), reg.serviceAccount());
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
