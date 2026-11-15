package dev.surge.catalog.config;

import dev.surge.common.security.InternalAccess;
import dev.surge.common.security.SurgeAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * catalog-service's rules in common's internal identity chain, mirroring the gateway's {@code SecurityConfig} for
 * {@code /api/catalog/**} (first match wins; health is open and anything not listed needs any valid token):
 *
 * <pre>
 * /api/catalog/admin, /api/catalog/admin/**   gateway, ADMIN
 * GET /api/catalog/**                          gateway, anonymous included (events, search)
 * </pre>
 *
 * Only the gateway is a trusted issuer for now; S3 adds {@code svc:notification} for the ticket's event lookup. The
 * admin interceptor ({@code AdminAccess}) repeats its check as a second layer.
 */
@Configuration(proxyBeanMethods = false)
class AccessRulesConfig {

    @Bean
    SurgeAccessRules catalogAccessRules() {
        return rules -> rules
                .requestMatchers("/api/catalog/admin", "/api/catalog/admin/**").access(InternalAccess.gatewayAdmin())
                .requestMatchers(HttpMethod.GET, "/api/catalog/**").access(InternalAccess.fromGateway());
    }
}
