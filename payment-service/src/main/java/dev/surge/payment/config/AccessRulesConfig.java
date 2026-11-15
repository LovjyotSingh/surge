package dev.surge.payment.config;

import dev.surge.common.security.InternalAccess;
import dev.surge.common.security.InternalTokens;
import dev.surge.common.security.SurgeAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * payment-service's rules in common's internal identity chain (first match wins; health is open and anything not
 * listed needs any valid token):
 *
 * <pre>
 * /api/payments/admin, /api/payments/admin/**   gateway, ADMIN (the chaos knobs; mirrors the gateway's rule)
 * /api/payments, /api/payments/**               svc:booking only (charge, status, refund)
 * </pre>
 *
 * The gateway answers 404 for the payments API ({@code PathGuardFilter}), so only booking calls it, with its own
 * token from S3 on. The chaos controller repeats its admin check as a second layer.
 */
@Configuration(proxyBeanMethods = false)
class AccessRulesConfig {

    @Bean
    SurgeAccessRules paymentAccessRules() {
        return rules -> rules
                .requestMatchers("/api/payments/admin", "/api/payments/admin/**").access(InternalAccess.gatewayAdmin())
                .requestMatchers("/api/payments", "/api/payments/**")
                .access(InternalAccess.service(InternalTokens.SVC_BOOKING));
    }
}
