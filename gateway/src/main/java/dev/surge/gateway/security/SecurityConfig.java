package dev.surge.gateway.security;

import dev.surge.gateway.error.SecurityProblemHandlers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

/**
 * The gateway is the only place that reads {@code Authorization: Bearer}. Rule table (first match wins):
 *
 * <pre>
 * /actuator/health(/**)                       public
 * /api/&#42;/admin/**                           ADMIN   (auth, payments, bookings, catalog, notifications)
 * /api/payments/**                            deny    (PathGuardFilter answers 404 before this point)
 * /api/auth/me                                USER
 * /api/auth/**                                public  (signup, login, JWKS)
 * GET /api/catalog/**                         public
 * GET /api/bookings/events/**                 public  (includes the SSE stream)
 * /api/bookings/holds/**, /orders/**          USER
 * GET /api/notifications/tickets/**           USER
 * anything else                               deny
 * </pre>
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, SecurityProblemHandlers problems,
            ReactiveJwtDecoder decoder) {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesToAuthorities());

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .authorizeExchange(auth -> auth
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers("/api/*/admin", "/api/*/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/payments", "/api/payments/**").denyAll()
                        .pathMatchers("/api/auth/me", "/api/auth/me/**").hasRole("USER")
                        .pathMatchers("/api/auth/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/catalog/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/bookings/events/**").permitAll()
                        .pathMatchers("/api/bookings/holds", "/api/bookings/holds/**",
                                "/api/bookings/orders", "/api/bookings/orders/**").hasRole("USER")
                        .pathMatchers(HttpMethod.GET, "/api/notifications/tickets/**").hasRole("USER")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        .jwt(jwt -> jwt.jwtDecoder(decoder).jwtAuthenticationConverter(converter)))
                .build();
    }

    /**
     * RS256 only, keys from the JWKS endpoint. {@code jwk-set-uri}, never {@code issuer-uri}: the auth service's issuer is the
     * plain string {@code surge-auth}, not a URL, so OIDC discovery cannot work. The issuer is compared as a string
     * ({@code Jwt.getIssuer()} would throw for a non-URL value and is never called).
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${surge.security.issuer:surge-auth}") String issuer) {
        // A hanging auth-service must not hang the gateway: bounded connect and response time, then fail closed.
        HttpClient http = HttpClient.create().option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .responseTimeout(java.time.Duration.ofSeconds(2));
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                .webClient(WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build())
                .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                requireClaims()));
        // Anything other than "this token is bad" (key set unreachable, timed out) is a service failure, not a 401: fail closed, 503.
        return token -> decoder.decode(token).onErrorMap(
                e -> !(e instanceof org.springframework.security.oauth2.jwt.JwtException),
                e -> new org.springframework.security.authentication.AuthenticationServiceException(
                        "Token signing keys are unavailable", e));
    }

    /** exp must be present (the default timestamp validator lets a token without exp live forever) and sub must be safe. */
    private static OAuth2TokenValidator<Jwt> requireClaims() {
        return jwt -> {
            if (jwt.getExpiresAt() == null) {
                return failure("Token has no expiry");
            }
            if (!JwtClaims.validUserId(jwt.getSubject())) {
                return failure("Token subject is not a valid user id");
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }

    private static Converter<Jwt, Flux<GrantedAuthority>> rolesToAuthorities() {
        return jwt -> Flux.fromIterable(JwtClaims.roles(jwt).stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList());
    }
}
