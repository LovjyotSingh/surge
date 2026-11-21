package dev.surge.gateway.error;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Last-resort handler: every gateway-side failure (no route, downstream unreachable, timeout, unexpected bug) becomes a
 * surge problem. Ordered before Boot's default handler.
 */
@Component
@Order(-2)
public class ProblemErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorHandler.class);

    /** Problem type of a refusal because the denylist or the JWKS is unavailable: {@code https://surge.dev/problems/auth-unavailable}. */
    public static final String AUTH_UNAVAILABLE_SLUG = "auth-unavailable";
    /** Seconds a client should wait before retrying {@code auth-unavailable}. */
    static final String AUTH_RETRY_AFTER_SECONDS = "1";

    private final ProblemWriter problems;

    public ProblemErrorHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        HttpStatusCode status = statusOf(ex);
        int code = status.value();
        if (code >= 500) {
            log.warn("Gateway error {} {}: {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                    ex.toString());
        }
        if (authUnavailable(ex)) {
            return problems.write(exchange, status, AUTH_UNAVAILABLE_SLUG, "Authentication unavailable",
                    "The sign-in check could not be completed right now; retry after " + AUTH_RETRY_AFTER_SECONDS
                            + " s", Map.of(HttpHeaders.RETRY_AFTER, AUTH_RETRY_AFTER_SECONDS));
        }
        return problems.write(exchange, status, slug(code), title(code), detail(ex, code));
    }

    /**
     * The token denylist (Redis) or the key set (JWKS) could not answer while verifying a token: the request is
     * refused (fail closed) with 503 (not 500, not 504), and unlike a plain {@code service-unavailable} this one says
     * so, with {@code Retry-After}, so a client can tell it apart and retry it.
     */
    private static boolean authUnavailable(Throwable ex) {
        for (Throwable c = ex; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof org.springframework.security.authentication.AuthenticationServiceException
                    || c instanceof org.springframework.security.oauth2.jwt.JwtException) {
                return true;
            }
        }
        return false;
    }

    private static HttpStatusCode statusOf(Throwable ex) {
        if (authUnavailable(ex)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        Throwable t = ex;
        for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
            if (t instanceof ResponseStatusException rse) {
                return rse.getStatusCode();
            }
            if (t instanceof ConnectException || t instanceof UnknownHostException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
            if (t instanceof TimeoutException || t.getClass().getName().contains("Timeout")) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String slug(int code) {
        return switch (code) {
            case 400 -> "bad-request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not-found";
            case 405 -> "method-not-allowed";
            case 406 -> "not-acceptable";
            case 413 -> "payload-too-large";
            case 415 -> "unsupported-media-type";
            case 429 -> "rate-limited";
            case 502, 503 -> "service-unavailable";
            case 504 -> "gateway-timeout";
            default -> code >= 500 ? "internal-error" : "bad-request";
        };
    }

    private static String title(int code) {
        return switch (code) {
            case 400 -> "Bad request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not found";
            case 405 -> "Method not allowed";
            case 429 -> "Too many requests";
            case 502, 503 -> "Service unavailable";
            case 504 -> "Gateway timeout";
            default -> code >= 500 ? "Internal error" : "Request error";
        };
    }

    /** Client errors may say why; server errors never leak internals. */
    private static String detail(Throwable ex, int code) {
        if (code >= 500) {
            return code == 504 ? "The upstream service did not answer in time"
                    : code == 500 ? "Unexpected gateway error" : "The upstream service is currently unavailable";
        }
        return ex instanceof ResponseStatusException rse && rse.getReason() != null ? rse.getReason()
                : title(code);
    }
}
