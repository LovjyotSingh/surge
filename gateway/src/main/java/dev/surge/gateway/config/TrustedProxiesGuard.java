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
 *   <li>A pattern that matches a public or documentation address (such as {@code .*}), or two or more of a few
 *       private ones (such as {@code .*[.]10} or a whole subnet): any client that reached the gateway from such an
 *       address could pick its own rate-limit key with {@code X-Forwarded-For}. One exact address passes.</li>
 *   <li>CIDR notation ({@code 10.30.250.10/32}): the value is a regex matched against one address string, so it
 *       would match nothing and trust no one.</li>
 * </ul>
 *
 * A private run (load test, local development) may trust no proxy. The pattern is the same one
 * {@link RouteConfig#clientAddress} and Spring Cloud Gateway's own {@code trusted-proxies} use. At runtime,
 * {@code ClientAddress} warns once if a peer it does not trust sends {@code X-Forwarded-For} (nginx always does).
 */
@Configuration(proxyBeanMethods = false)
class TrustedProxiesGuard {

    /** Addresses no proxy of ours ever has: documentation (RFC 5737, RFC 3849) and public ones. Any match refuses. */
    private static final List<String> NEVER_A_PROXY = List.of("203.0.113.7", "2001:db8:0:0:0:0:0:7", "1.2.3.4",
            "8.8.8.8");
    /** Private addresses, two of them in nginx's own edge subnet: a pattern may match one, never two. */
    private static final List<String> PRIVATE_PROBES = List.of("192.168.1.10", "172.16.0.10", "10.0.0.10",
            "10.30.250.11", "10.30.250.12");

    TrustedProxiesGuard(@Value("${surge.security.public-deployment:false}") boolean publicDeployment,
            @Value("${surge.security.trusted-proxies:}") String trustedProxies) {
        if (!publicDeployment) {
            return;
        }
        if (trustedProxies == null || trustedProxies.isBlank()) {
            throw new IllegalStateException("SURGE_TRUSTED_PROXIES must name nginx's address when "
                    + "SURGE_PUBLIC_DEPLOYMENT=true; otherwise every client shares one rate-limit bucket");
        }
        if (trustedProxies.contains("/")) {
            throw new IllegalStateException("SURGE_TRUSTED_PROXIES is a regex over one address, e.g. "
                    + "10[.]30[.]250[.]10; CIDR notation is not supported");
        }
        Pattern pattern = Pattern.compile(trustedProxies);
        long privateMatches = PRIVATE_PROBES.stream().filter(address -> pattern.matcher(address).matches()).count();
        if (privateMatches > 1 || NEVER_A_PROXY.stream().anyMatch(address -> pattern.matcher(address).matches())) {
            throw new IllegalStateException("SURGE_TRUSTED_PROXIES must match only nginx's address when "
                    + "SURGE_PUBLIC_DEPLOYMENT=true; this pattern also trusts other addresses");
        }
    }
}
