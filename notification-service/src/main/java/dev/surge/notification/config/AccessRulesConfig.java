package dev.surge.notification.config;

import dev.surge.common.security.InternalAccess;
import dev.surge.common.security.SurgeAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * notification-service's rules in common's internal identity chain, mirroring the gateway's {@code SecurityConfig}
 * for {@code /api/notifications/**} (first match wins; health is open and anything not listed needs any valid token):
 *
 * <pre>
 * /api/notifications/admin, /api/notifications/admin/**   gateway, ADMIN (DLQ, audit)
 * GET /api/notifications/tickets/**                        gateway, signed-in user (the owner's PDF)
 * </pre>
 *
 * Only the gateway is a trusted issuer. The controllers repeat their checks through {@code CurrentIdentity}.
 */
@Configuration(proxyBeanMethods = false)
class AccessRulesConfig {

    @Bean
    SurgeAccessRules notificationAccessRules() {
        return rules -> rules
                .requestMatchers("/api/notifications/admin", "/api/notifications/admin/**")
                .access(InternalAccess.gatewayAdmin())
                .requestMatchers(HttpMethod.GET, "/api/notifications/tickets/**").access(InternalAccess.gatewayUser());
    }
}
