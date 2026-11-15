package dev.surge.auth.config;

import dev.surge.common.security.InternalAccess;
import dev.surge.common.security.InternalTokens;
import dev.surge.common.security.SurgeAccessRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * auth-service's rules in common's internal identity chain, mirroring the gateway's {@code SecurityConfig} for
 * {@code /api/auth/**} (first match wins; health is open and anything not listed needs any valid token):
 *
 * <pre>
 * GET /api/auth/.well-known/jwks.json   open: public key material the gateway fetches directly, without a token
 * /api/auth/admin, /api/auth/admin/**   gateway, ADMIN
 * /api/auth/me, /api/auth/me/**         gateway, signed-in user
 * /api/auth/**                          gateway, anonymous included (signup, login)
 * /internal/users/*                     svc:notification only (the ticket email lookup)
 * </pre>
 *
 * The controllers repeat their own checks through {@code CurrentIdentity} as a second layer.
 */
@Configuration(proxyBeanMethods = false)
class AccessRulesConfig {

    @Bean
    SurgeAccessRules authAccessRules() {
        return rules -> rules
                .requestMatchers(HttpMethod.GET, "/api/auth/.well-known/jwks.json").permitAll()
                .requestMatchers("/api/auth/admin", "/api/auth/admin/**").access(InternalAccess.gatewayAdmin())
                .requestMatchers("/api/auth/me", "/api/auth/me/**").access(InternalAccess.gatewayUser())
                .requestMatchers("/api/auth/**").access(InternalAccess.fromGateway())
                .requestMatchers("/internal/users/*")
                .access(InternalAccess.service(InternalTokens.SVC_NOTIFICATION));
    }
}
