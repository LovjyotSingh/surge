package dev.surge.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * {@code POST /api/auth/logout}, answered by the gateway itself (SEC-18): it is the one place that has both the
 * verified user JWT and Redis. The caller's token ({@code jti}) goes on the {@link TokenDenylist} until it expires, so
 * the same token is refused (401) by every gateway instance from the next request on; then 204.
 *
 * <p>The request never reaches auth-service: a {@link RouterFunction} is matched by WebFlux's
 * {@code RouterFunctionMapping} (order -1) before the gateway's route mapping (order 1), so the {@code auth-public}
 * route ({@code /api/auth/**}) cannot pick it up. {@code SecurityConfig} requires a valid token for it and denies every
 * other method on the path. Nothing about the token is logged.
 */
@Configuration(proxyBeanMethods = false)
class LogoutConfig {

    static final String PATH = "/api/auth/logout";

    private static final Logger log = LoggerFactory.getLogger(LogoutConfig.class);

    @Bean
    RouterFunction<ServerResponse> logoutRoute(TokenDenylist denylist) {
        return RouterFunctions.route(RequestPredicates.POST(PATH), request -> request.principal()
                .ofType(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                .flatMap(jwt -> revoke(denylist, jwt))
                .then(ServerResponse.noContent().build()));
    }

    private static Mono<Void> revoke(TokenDenylist denylist, Jwt jwt) {
        return denylist.revoke(jwt.getId(), jwt.getExpiresAt()).onErrorMap(e -> {
            log.warn("Logout could not revoke a token: {}", e.getClass().getSimpleName());
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Logout is unavailable, try again", e);
        });
    }
}
