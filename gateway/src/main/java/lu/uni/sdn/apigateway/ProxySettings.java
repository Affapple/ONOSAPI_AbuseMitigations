package lu.uni.sdn.apigateway;

import lombok.Value;

import java.util.Dictionary;

/**
 * Settings of the read-forwarding proxy (/gw/v1/*). The service account's user
 * name comes from tenants.json ("serviceAccount"); only its password is here.
 * The account is viewer-only, so it cannot write even if the proxy is buggy.
 * No admin credential is stored: admin writes are forwarded with the caller's
 * own Authorization header.
 */
@Value
public class ProxySettings {
    public static final String PROXY_BASE_URL = "proxyBaseUrl";
    public static final String PROXY_BASE_URL_DEFAULT = "http://127.0.0.1:8181/onos/v1/";
    public static final String SERVICE_ACCOUNT_PASSWORD = "serviceAccountPassword";
    public static final String SERVICE_ACCOUNT_PASSWORD_DEFAULT = "gw-svc-pw";

    /** Upstream base URL, ending in "/", e.g. http://127.0.0.1:8181/onos/v1/. */
    String baseUrl;
    String serviceAccountPassword;

    /**
     * Parses the proxy settings.
     *
     * @param p component properties (null: defaults)
     * @return settings
     */
    public static ProxySettings from(Dictionary<?, ?> p) {
        String url = Props.string(p, PROXY_BASE_URL, PROXY_BASE_URL_DEFAULT);
        return new ProxySettings(url.endsWith("/") ? url : url + "/",
                Props.string(p, SERVICE_ACCOUNT_PASSWORD, SERVICE_ACCOUNT_PASSWORD_DEFAULT));
    }
}
