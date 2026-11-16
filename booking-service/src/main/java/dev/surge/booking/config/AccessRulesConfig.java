package dev.surge.booking.config;

import dev.surge.common.security.InternalAccess;
import dev.surge.common.security.InternalTokens;
import dev.surge.common.security.SurgeAccessRules;
import dev.surge.common.security.SurgeEnv;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * booking-service's rules in common's internal identity chain, mirroring the gateway's {@code SecurityConfig} for
 * {@code /api/bookings/**} (first match wins; health is open; anything not listed is denied):
 *
 * <pre>
 * /api/bookings/admin, /api/bookings/admin/**           gateway, ADMIN
 * GET /api/bookings/events, /api/bookings/events/**     gateway, anonymous included (also the SSE stream)
 * /api/bookings/holds(/**), /api/bookings/orders(/**)   gateway, signed-in user
 * GET /internal/orders/*                                svc:notification only (the ticket's order lookup)
 * GET API docs and Swagger UI                           open in SURGE_ENV=dev only, denied otherwise
 * </pre>
 *
 * The admin interceptor ({@code AdminAccess}), {@code @CurrentUser} and the internal controller repeat their checks
 * as a second layer.
 */
@Configuration(proxyBeanMethods = false)
class AccessRulesConfig {

    /** springdoc's JSON and UI (paths from application.yml, plus the UI's own assets). */
    static final String[] API_DOCS = {"/api/bookings/v3/api-docs", "/api/bookings/v3/api-docs/**",
        "/api/bookings/docs", "/api/bookings/docs/**", "/api/bookings/swagger-ui/**", "/swagger-ui/**",
        "/swagger-ui.html"};

    @Bean
    SurgeAccessRules bookingAccessRules(SurgeEnv env) {
        return rules -> {
            rules.requestMatchers("/api/bookings/admin", "/api/bookings/admin/**").access(InternalAccess.gatewayAdmin())
                    .requestMatchers(HttpMethod.GET, "/api/bookings/events", "/api/bookings/events/**")
                    .access(InternalAccess.fromGateway())
                    .requestMatchers("/api/bookings/holds", "/api/bookings/holds/**", "/api/bookings/orders",
                            "/api/bookings/orders/**").access(InternalAccess.gatewayUser())
                    .requestMatchers(HttpMethod.GET, "/internal/orders/*")
                    .access(InternalAccess.service(InternalTokens.SVC_NOTIFICATION));
            // A developer's browser on the service port, without a token; never outside dev (S8 also turns
            // springdoc off there). The gateway routes none of these paths.
            if (env == SurgeEnv.DEV) {
                rules.requestMatchers(HttpMethod.GET, API_DOCS).permitAll();
            } else {
                rules.requestMatchers(API_DOCS).denyAll();
            }
        };
    }
}
