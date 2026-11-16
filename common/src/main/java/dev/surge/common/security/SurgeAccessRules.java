package dev.surge.common.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * A service's own authorization rules, added to the internal identity chain between the built-in actuator rules
 * ({@code /actuator/health/**} open, the rest denied) and the final {@code anyRequest().denyAll()}: a path or method
 * no rule names is refused. Beans are applied in {@code @Order}; first match wins. Example:
 *
 * <pre>
 * &#64;Bean
 * SurgeAccessRules bookingRules() {
 *     return rules -&gt; rules
 *             .requestMatchers("/api/bookings/admin/**").access(InternalAccess.gatewayAdmin())
 *             .requestMatchers("/api/bookings/holds/**", "/api/bookings/orders/**").access(InternalAccess.gatewayUser())
 *             .requestMatchers("/internal/orders/*").access(InternalAccess.service(InternalTokens.SVC_NOTIFICATION));
 * }
 * </pre>
 */
@FunctionalInterface
public interface SurgeAccessRules {

    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry rules);
}
