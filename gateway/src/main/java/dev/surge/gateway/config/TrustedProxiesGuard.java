package dev.surge.gateway.config;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * S10 (SEC-11): a public deployment ({@code surge.security.public-deployment=true}, env
 * {@code SURGE_PUBLIC_DEPLOYMENT}) refuses to start unless {@code surge.security.trusted-proxies} (env
 * {@code SURGE_TRUSTED_PROXIES}) names the proxy in front of it, and names nothing more.
 *
 * <ul>
 *   <li>Blank: every client would be keyed by nginx's address, so one attacker's ten bad logins would answer 429 to
 *       every user's login and signup.</li>
 *   <li>A pattern that also matches a documentation address (TEST-NET-3, 2001:db8::/32), such as {@code .*}: any
 *       client that reached the gateway could then pick its own rate-limit key with {@code X-Forwarded-For}.</li>
 * </ul>
 *
 * A private run (load test, local development) may trust no proxy. The pattern is the same one
 * {@link RouteConfig#clientAddress} and Spring Cloud Gateway's own {@code trusted-proxies} use.
 */
@Configuration(proxyBeanMethods = false)
class TrustedProxiesGuard {

    /** Addresses no real proxy ever has (RFC 5737, RFC 3849), in the forms the gateway matches against. */
    private static final List<String> NEVER_A_PROXY = List.of("203.0.113.7", "2001:db8:0:0:0:0:0:7");

    TrustedProxiesGuard(@Value("${surge.security.public-deployment:false}") boolean publicDeployment,
            @Value("${surge.security.trusted-proxies:}") String trustedProxies) {
        if (!publicDeployment) {
            return;
        }
        if (trustedProxies == null || trustedProxies.isBlank()) {
            throw new IllegalStateException("SURGE_TRUSTED_PROXIES must name nginx's address when "
                    + "SURGE_PUBLIC_DEPLOYMENT=true; otherwise every client shares one rate-limit bucket");
        }
        Pattern pattern = Pattern.compile(trustedProxies);
        if (NEVER_A_PROXY.stream().anyMatch(address -> pattern.matcher(address).matches())) {
            throw new IllegalStateException("SURGE_TRUSTED_PROXIES must match only nginx's address when "
                    + "SURGE_PUBLIC_DEPLOYMENT=true; this pattern also trusts arbitrary addresses");
        }
    }
}
