package dev.surge.gateway.error;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 401 and 403 as surge problems; the {@code WWW-Authenticate} challenge is kept on 401. */
@Component
public class SecurityProblemHandlers implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final ProblemWriter problems;

    public SecurityProblemHandlers(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return problems.write(exchange, HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized",
                "A valid bearer token is required", Map.of("WWW-Authenticate", "Bearer"));
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return problems.write(exchange, HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "You are not allowed to do this");
    }
}
