package dev.surge.payment.config;

import dev.surge.common.security.InternalAccess;
import dev.surge.common.security.InternalTokens;
import dev.surge.common.security.SurgeAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * payment-service's rules in common's internal identity chain (first match wins; health is open; anything not listed
 * is denied):
 *
 * <pre>
 * /api/payments/admin, /api/payments/admin/**   gateway, ADMIN (the chaos knobs; mirrors the gateway's rule)
 * POST /api/payments                            svc:booking only (charge)
 * GET  /api/payments                            svc:booking only (status by reference)
 * POST /api/payments/{id}/refund                svc:booking only (refund)
 * </pre>
 *
 * The gateway answers 404 for the payments API ({@code PathGuardFilter}), so only booking calls it, signed with its
 * own key. The controllers repeat their checks as a second layer.
 */
@Configuration(proxyBeanMethods = false)
class AccessRulesConfig {

    @Bean
    SurgeAccessRules paymentAccessRules() {
        AuthorizationManager<RequestAuthorizationContext> booking = InternalAccess.service(InternalTokens.SVC_BOOKING);
        return rules -> rules
                .requestMatchers("/api/payments/admin", "/api/payments/admin/**").access(InternalAccess.gatewayAdmin())
                .requestMatchers(HttpMethod.POST, "/api/payments", "/api/payments/*/refund").access(booking)
                .requestMatchers(HttpMethod.GET, "/api/payments").access(booking);
    }
}
