package lu.uni.sdn.apigateway;

import lu.uni.sdn.apigateway.quota.QuotaLimits;
import lu.uni.sdn.apigateway.ratelimit.RateLimitSettings;
import lu.uni.sdn.apigateway.throttle.ThrottleSettings;
import lu.uni.sdn.apigateway.validation.ValidationLimits;
import org.junit.Test;

import java.util.Dictionary;
import java.util.Hashtable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B8: each module parses its own settings (defaults, invalid values, cross-field checks).
 */
public class SettingsTest {

    private static final double EPS = 1e-9;

    private static Dictionary<String, Object> props(Object... kv) {
        Hashtable<String, Object> d = new Hashtable<>();
        for (int i = 0; i < kv.length; i += 2) {
            d.put((String) kv[i], kv[i + 1]);
        }
        return d;
    }

    @Test
    public void defaults() {
        assertTrue(ModuleToggles.from(null).isAdaptiveEnabled());
        assertEquals("http://127.0.0.1:8181/onos/v1/", ProxySettings.from(null).getBaseUrl());
        assertEquals(10.0, RateLimitSettings.from(null).getRateLimitRps(), EPS);
        assertEquals(20, RateLimitSettings.from(null).getRateLimitBurst());
        assertEquals(100, QuotaLimits.from(null).getMaxFlowsPerTenant());
        assertEquals(60, QuotaLimits.from(null).getChurnWindowSeconds());
        assertEquals(30000, ValidationLimits.from(null).getMaxPriority());
        ThrottleSettings t = ThrottleSettings.from(null);
        assertEquals(ThrottleSettings.ADAPTIVE, t.getMode());
        assertEquals(ThrottleSettings.EFFECT_RATE, t.getFixedEffect());
        assertEquals(0.1, t.getFixedRateFactor(), EPS);
        assertEquals("empty dictionary = defaults", ThrottleSettings.from(null), ThrottleSettings.from(props()));
    }

    @Test
    public void valuesAreParsedFromStringsToo() {
        Dictionary<String, Object> p = props(ModuleToggles.QUOTA_ENABLED, "false",
                RateLimitSettings.RATE_LIMIT_RPS, "2.5", QuotaLimits.MAX_FLOWS, 7,
                ThrottleSettings.THROTTLE_MODE, "fixed", ProxySettings.PROXY_BASE_URL, "http://h:1/onos/v1");
        assertFalse(ModuleToggles.from(p).isQuotaEnabled());
        assertEquals(2.5, RateLimitSettings.from(p).getRateLimitRps(), EPS);
        assertEquals(7, QuotaLimits.from(p).getMaxFlowsPerTenant());
        assertTrue(ThrottleSettings.from(p).isFixed());
        assertEquals("trailing slash added", "http://h:1/onos/v1/", ProxySettings.from(p).getBaseUrl());
    }

    @Test
    public void invalidValuesFallBackToDefaults() {
        Dictionary<String, Object> p = props(RateLimitSettings.RATE_LIMIT_RPS, "-1",
                RateLimitSettings.RATE_LIMIT_BURST, "abc", QuotaLimits.MAX_INTENTS, 0,
                ThrottleSettings.THROTTLE_MODE, "panic", ThrottleSettings.FIXED_EFFECT, "nuke",
                ThrottleSettings.WEIGHT_ACL, "-3");
        assertEquals(10.0, RateLimitSettings.from(p).getRateLimitRps(), EPS);
        assertEquals(20, RateLimitSettings.from(p).getRateLimitBurst());
        assertEquals(20, QuotaLimits.from(p).getMaxIntentsPerTenant());
        ThrottleSettings t = ThrottleSettings.from(p);
        assertEquals(ThrottleSettings.ADAPTIVE, t.getMode());
        assertEquals(ThrottleSettings.EFFECT_RATE, t.getFixedEffect());
        assertEquals(3.0, t.getWeightAcl(), EPS);
        assertEquals("weight 0 is allowed (module not scored)", 0.0,
                ThrottleSettings.from(props(ThrottleSettings.WEIGHT_ACL, 0)).getWeightAcl(), EPS);
    }

    @Test
    public void crossFieldChecksLiveInTheModule() {
        ThrottleSettings t = ThrottleSettings.from(props(ThrottleSettings.LEVEL1_THRESHOLD, 30.0,
                ThrottleSettings.LEVEL2_THRESHOLD, 20.0));
        assertEquals("thresholds not increasing -> all defaults", 10.0, t.threshold(1), EPS);
        assertEquals(25.0, t.threshold(2), EPS);
        assertEquals(50.0, t.threshold(3), EPS);
        assertEquals(40.0, ThrottleSettings.from(props(ThrottleSettings.LEVEL3_THRESHOLD, 40.0)).threshold(3), EPS);
        assertEquals("cooldownMax >= cooldown", 100, ThrottleSettings.from(props(ThrottleSettings.COOLDOWN, 100,
                ThrottleSettings.COOLDOWN_MAX, 50)).getCooldownMaxSeconds());
        ValidationLimits v = ValidationLimits.from(props(ValidationLimits.MIN_PRIORITY, 500,
                ValidationLimits.MAX_PRIORITY, 400));
        assertEquals(100, v.getMinPriority());
        assertEquals(30000, v.getMaxPriority());
    }
}
