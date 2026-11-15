package dev.surge.booking.config;

import dev.surge.common.security.InternalAccess;
import dev.surge.common.security.SurgeAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * booking-service's rules in common's internal identity chain, mirroring the gateway's {@code SecurityConfig} for
 * {@code /api/bookings/**} (first match wins; health is open and anything not listed needs any valid token):
 *
 * <pre>
 * /api/bookings/admin, /api/bookings/admin/**           gateway, ADMIN
 * GET /api/bookings/events, /api/bookings/events/**     gateway, anonymous included (also the SSE stream)
 * /api/bookings/holds(/**), /api/bookings/orders(/**)   gateway, signed-in user
 * </pre>
 *
 * Only the gateway is a trusted issuer for now; S3 adds {@code svc:notification} with {@code /internal/orders/*}.
 * The admin interceptor ({@code AdminAccess}) and {@code @CurrentUser} repeat their checks as a second layer.
 */
@Configuration(proxyBeanMethods = false)
class AccessRulesConfig {

    @Bean
    SurgeAccessRules bookingAccessRules() {
        return rules -> rules
                .requestMatchers("/api/bookings/admin", "/api/bookings/admin/**").access(InternalAccess.gatewayAdmin())
                .requestMatchers(HttpMethod.GET, "/api/bookings/events", "/api/bookings/events/**")
                .access(InternalAccess.fromGateway())
                .requestMatchers("/api/bookings/holds", "/api/bookings/holds/**", "/api/bookings/orders",
                        "/api/bookings/orders/**").access(InternalAccess.gatewayUser());
    }
}
