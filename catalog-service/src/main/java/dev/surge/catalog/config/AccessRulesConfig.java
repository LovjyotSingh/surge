package dev.surge.catalog.config;

import dev.surge.common.security.InternalAccess;
import dev.surge.common.security.InternalTokens;
import dev.surge.common.security.SurgeAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * catalog-service's rules in common's internal identity chain, mirroring the gateway's {@code SecurityConfig} for
 * {@code /api/catalog/**} (first match wins; health is open; anything not listed is denied):
 *
 * <pre>
 * /api/catalog/admin, /api/catalog/admin/**   gateway, ADMIN
 * GET /api/catalog/events/{numeric id}        gateway (anonymous included) OR svc:notification (the ticket's event)
 * GET /api/catalog/**                         gateway, anonymous included (list, search)
 * </pre>
 *
 * notification may read one event by id and nothing else: the list, search and admin stay gateway-only. The admin
 * interceptor ({@code AdminAccess}) repeats its check as a second layer.
 */
@Configuration(proxyBeanMethods = false)
class AccessRulesConfig {

    @Bean
    SurgeAccessRules catalogAccessRules() {
        AuthorizationManager<RequestAuthorizationContext> eventById = AuthorizationManagers.anyOf(
                InternalAccess.fromGateway(), InternalAccess.service(InternalTokens.SVC_NOTIFICATION));
        return rules -> rules
                .requestMatchers("/api/catalog/admin", "/api/catalog/admin/**").access(InternalAccess.gatewayAdmin())
                .requestMatchers(HttpMethod.GET, "/api/catalog/events/{id:[0-9]+}").access(eventById)
                .requestMatchers(HttpMethod.GET, "/api/catalog/**").access(InternalAccess.fromGateway());
    }
}
