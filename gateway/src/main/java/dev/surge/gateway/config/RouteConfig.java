package dev.surge.gateway.config;

import dev.surge.gateway.error.ProblemWriter;
import dev.surge.gateway.ratelimit.KeyResolvers;
import dev.surge.gateway.ratelimit.RateLimitFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * The route table. Targets are {@code lb://<service>} in the app and overridable ({@code surge.routes.*-uri}) so tests
 * can point them at mock servers. Routes are listed most specific first. Authorization is NOT decided here but in
 * {@code SecurityConfig}, which runs before routing: a route existing never makes a path reachable.
 */
@Configuration
class RouteConfig {

    private static final String RESPONSE_TIMEOUT = "response-timeout";

    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, RedisRateLimiter limiter, ProblemWriter problems,
            @Value("${surge.routes.auth-uri:lb://auth-service}") String auth,
            @Value("${surge.routes.catalog-uri:lb://catalog-service}") String catalog,
            @Value("${surge.routes.booking-uri:lb://booking-service}") String booking,
            @Value("${surge.routes.notification-uri:lb://notification-service}") String notification,
            @Value("${surge.routes.payment-uri:lb://payment-service}") String payment) {

        // holds and orders: 2 requests/s, burst 10, per user, one bucket per route
        RateLimitFilter holdsLimit = new RateLimitFilter(limiter, problems, "holds", KeyResolvers.user(), 2, 10);
        RateLimitFilter ordersLimit = new RateLimitFilter(limiter, problems, "orders", KeyResolvers.user(), 2, 10);
        // login and signup share one per-IP bucket: 5 requests/s, burst 10
        RateLimitFilter authLimit = new RateLimitFilter(limiter, problems, "auth-entry", KeyResolvers.clientIp(), 5,
                10);

        return builder.routes()
                // --- ADMIN (the owning service) ---
                .route("auth-admin", r -> r.path("/api/auth/admin/**").uri(auth))
                .route("payments-admin", r -> r.path("/api/payments/admin/**").uri(payment))
                .route("bookings-admin", r -> r.path("/api/bookings/admin/**").uri(booking))
                .route("catalog-admin", r -> r.path("/api/catalog/admin/**").uri(catalog))
                .route("notifications-admin", r -> r.path("/api/notifications/admin/**").uri(notification))
                // --- auth ---
                .route("auth-me", r -> r.path("/api/auth/me").uri(auth))
                .route("auth-entry", r -> r.method(HttpMethod.POST).and()
                        .path("/api/auth/login", "/api/auth/signup")
                        .filters(f -> f.filter(authLimit)).uri(auth))
                .route("auth-public", r -> r.path("/api/auth/**").uri(auth))
                // --- catalog (public reads) ---
                .route("catalog", r -> r.method(HttpMethod.GET).and().path("/api/catalog/**")
                        .filters(f -> f.circuitBreaker(c -> c.setName("catalog")
                                .setFallbackUri("forward:/fallback/catalog")))
                        .uri(catalog))
                // --- booking: the SSE stream has no response timeout; other public event reads use the default ---
                .route("booking-events-stream", r -> r.method(HttpMethod.GET).and()
                        .path("/api/bookings/events/*/stream")
                        .metadata(RESPONSE_TIMEOUT, -1)
                        .uri(booking))
                .route("booking-events", r -> r.method(HttpMethod.GET).and().path("/api/bookings/events/**")
                        .uri(booking))
                .route("booking-holds", r -> r.path("/api/bookings/holds/**")
                        .filters(f -> f.filter(holdsLimit)
                                .circuitBreaker(c -> c.setName("booking").setFallbackUri("forward:/fallback/booking")))
                        .uri(booking))
                .route("booking-orders", r -> r.path("/api/bookings/orders/**")
                        .filters(f -> f.filter(ordersLimit)
                                .circuitBreaker(c -> c.setName("booking").setFallbackUri("forward:/fallback/booking")))
                        .uri(booking))
                // --- notification ---
                .route("notification-tickets", r -> r.method(HttpMethod.GET).and()
                        .path("/api/notifications/tickets/**").uri(notification))
                .build();
    }

    /**
     * Resilience4j's default time limiter is 1 s, far below what a checkout may take (payment client alone allows 2 s plus
     * a retry), so the limiter is lifted to {@code surge.gateway.breaker-timeout}. Only connection failures and timeouts open the
     * breaker; a downstream 4xx/5xx is passed through untouched.
     */
    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> breakerDefaults(
            @Value("${surge.gateway.breaker-timeout:10s}") Duration timeout) {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(20)
                        .minimumNumberOfCalls(10)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(timeout).build())
                .build());
    }
}
