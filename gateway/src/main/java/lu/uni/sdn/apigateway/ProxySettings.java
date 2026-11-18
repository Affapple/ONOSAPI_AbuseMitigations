package lu.uni.sdn.apigateway;

import lombok.Value;

/**
 * Settings of the read-forwarding proxy (/gw/v1/*). The service account's user
 * name comes from tenants.json ("serviceAccount"); only its password is here.
 * The account is viewer-only, so it cannot write even if the proxy is buggy.
 * No admin credential is stored: admin writes are forwarded with the caller's
 * own Authorization header.
 */
@Value
public class ProxySettings {
    /** Upstream base URL, ending in "/", e.g. http://127.0.0.1:8181/onos/v1/. */
    String baseUrl;
    String serviceAccountPassword;
}
