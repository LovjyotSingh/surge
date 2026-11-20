package dev.surge.gateway.security;

import com.nimbusds.jose.jwk.JWKSet;
import dev.surge.gateway.error.SecurityProblemHandlers;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
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
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * The gateway is the only place that reads {@code Authorization: Bearer}. Rule table (first match wins):
 *
 * <pre>
 * /actuator/health(/**)                       public
 * /api/&#42;/admin/**                           ADMIN   (auth, payments, bookings, catalog, notifications)
 * /api/payments/**                            deny    (PathGuardFilter answers 404 before this point)
 * /api/auth/me                                USER
 * POST /api/auth/logout                       any valid token (answered here, never routed: LogoutConfig)
 * /api/auth/logout (other methods)            deny
 * POST /api/auth/verify, /verify/resend       USER    (email verification code, S5)
 * /api/auth/verify/** (anything else)         deny
 * /api/auth/**                                public  (only signup, login and GET of the JWKS are routed)
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

    /** The {@code aud} every user token must carry; auth-service's {@code TokenService.AUDIENCE}. */
    static final String AUDIENCE = "surge-api";
    /** Email verification; RouteConfig gives both paths their own rate-limited routes to auth. */
    static final String VERIFY = "/api/auth/verify";

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
                        // answered by the gateway itself (LogoutConfig): any valid token may revoke itself
                        .pathMatchers(HttpMethod.POST, LogoutConfig.PATH).authenticated()
                        .pathMatchers(LogoutConfig.PATH, LogoutConfig.PATH + "/**").denyAll()
                        // email verification (S5): the signed-in user enters the code or asks for a new one
                        .pathMatchers(HttpMethod.POST, VERIFY, VERIFY + "/resend").hasRole("USER")
                        .pathMatchers(VERIFY, VERIFY + "/**").denyAll()
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
     * auth-service's key set ({@code jwk-set-uri}), cached with a refetch cooldown ({@link JwksCache}). {@code jwk-set-uri},
     * never {@code issuer-uri}: the auth service's issuer is the plain string {@code surge-auth}, not a URL, so OIDC
     * discovery cannot work. The clock is the {@code jwksClock} bean when one exists (tests move it), else system UTC.
     */
    @Bean
    JwksCache jwksCache(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Qualifier("jwksClock") ObjectProvider<Clock> jwksClock) {
        // A hanging auth-service must not hang the gateway: bounded connect and response time, then fail closed.
        HttpClient http = HttpClient.create().option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .responseTimeout(Duration.ofSeconds(2));
        WebClient web = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
        return new JwksCache(() -> web.get().uri(jwkSetUri).retrieve().bodyToMono(String.class)
                .timeout(Duration.ofSeconds(3))
                .handle((body, sink) -> {
                    try {
                        sink.next(JWKSet.parse(body));
                    } catch (java.text.ParseException e) {
                        sink.error(new IllegalStateException("The key set response is not a JWKS"));
                    }
                }), jwksClock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * RS256 only, keys from {@link JwksCache}. A token is accepted only with the issuer {@code surge-auth} (compared as a
     * string: {@code Jwt.getIssuer()} would throw for a non-URL value and is never called), an {@code aud} containing
     * {@value #AUDIENCE}, an {@code exp}, a safe {@code sub}, a {@code jti}, and a {@code jti} that is not on the
     * {@link TokenDenylist} (logout).
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(JwksCache jwks, TokenDenylist denylist,
            @Value("${surge.security.issuer:surge-auth}") String issuer,
            @Value("${surge.security.allow-loadtest-subjects:false}") boolean allowLoadtestSubjects) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSource(jwks::keysFor)
                .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                audience(),
                requireTokenId(),
                requireClaims(allowLoadtestSubjects)));
        // Anything other than "this token is bad" (key set or denylist unreachable, timed out) is a service failure,
        // not a 401: fail closed, 503.
        return token -> decoder.decode(token)
                .flatMap(jwt -> denylist.isRevoked(jwt.getId()).flatMap(revoked -> revoked
                        ? Mono.<Jwt>error(new BadJwtException("Token has been revoked"))
                        : Mono.just(jwt)))
                .onErrorMap(
                        e -> !(e instanceof org.springframework.security.oauth2.jwt.JwtException),
                        e -> new org.springframework.security.authentication.AuthenticationServiceException(
                                "Token signing keys or the token denylist are unavailable", e));
    }

    /** The user token must be meant for the API (SEC-18): any other token auth might sign one day is refused. */
    static OAuth2TokenValidator<Jwt> audience() {
        return new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(AUDIENCE));
    }

    /** A {@code jti} is what logout revokes, so a token without a well-formed one could never be logged out. */
    static OAuth2TokenValidator<Jwt> requireTokenId() {
        return jwt -> JwtClaims.validTokenId(jwt.getId()) ? OAuth2TokenValidatorResult.success()
                : failure("Token has no valid jti");
    }

    /** exp must be present (the default timestamp validator lets a token without exp live forever) and sub must be safe. */
    static OAuth2TokenValidator<Jwt> requireClaims(boolean allowLoadtest) {
        return jwt -> {
            if (jwt.getExpiresAt() == null) {
                return failure("Token has no expiry");
            }
            if (!JwtClaims.validUserId(jwt.getSubject())) {
                return failure("Token subject is not a valid user id");
            }
            if (!allowLoadtest && JwtClaims.isLoadtest(jwt)) {
                return failure("Load-test tokens are not accepted");
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
