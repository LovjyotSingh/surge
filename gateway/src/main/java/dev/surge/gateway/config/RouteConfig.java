package dev.surge.gateway.config;

import dev.surge.gateway.error.ProblemWriter;
import dev.surge.gateway.ratelimit.ClientAddress;
import dev.surge.gateway.ratelimit.KeyResolvers;
import dev.surge.gateway.ratelimit.RateLimitFilter;
import dev.surge.gateway.ratelimit.RateLimitFilter.Limit;
import dev.surge.gateway.ratelimit.RateLimitFilter.OnLimiterFailure;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
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

    /** The circuit breaker (and its bulkhead, {@link BulkheadConfig}) on the hold and order routes. */
    static final String BOOKING_BREAKER = "booking";
    /** The circuit breaker (and its bulkhead) on catalog's public reads. */
    static final String CATALOG_BREAKER = "catalog";

    private static final String RESPONSE_TIMEOUT = "response-timeout";
    private static final String AUDIENCE = RouteAudiences.METADATA_KEY;

    /**
     * The client address for per-IP buckets. {@code surge.security.trusted-proxies} (env {@code SURGE_TRUSTED_PROXIES},
     * compose sets nginx's subnet) is also Spring Cloud Gateway's {@code trusted-proxies} (application.yml), so the
     * gateway trusts the same proxies for rate limiting and for the forwarding headers it passes on.
     */
    @Bean
    ClientAddress clientAddress(@Value("${surge.security.trusted-proxies:}") String trustedProxies) {
        return new ClientAddress(trustedProxies);
    }

    /**
     * Buckets (Redis token buckets, one per name; per IP means per {@link ClientAddress}):
     *
     * <pre>
     * auth-login   /api/auth/login           per IP    5/s, burst 10   fails CLOSED (503) without Redis
     * auth-signup  /api/auth/signup          per IP    3/min, burst 3  fails CLOSED (each signup sends an email)
     * verify       /api/auth/verify          per user 10/min, burst 10 fails CLOSED (entering the emailed code)
     * verify-ip    /api/auth/verify          per IP   30/min, burst 30 fails CLOSED
     * resend       /api/auth/verify/resend   per user  3/min, burst 3  fails CLOSED (sends an email)
     * resend-ip    /api/auth/verify/resend   per IP    5/min, burst 5  fails CLOSED
     * auth-me      /api/auth/me              per user  2/s, burst 4
     * catalog      GET /api/catalog/**       per IP   10/s, burst 20   (list, search, detail)
     * events       GET booking events        per IP   20/s, burst 40
     * stream-open  GET .../events/{id}/stream per IP   2/s, burst 20   (opening a stream, not its events; CGNAT and
     *                                                                   office networks share an IP at sale start)
     * holds        /api/bookings/holds/**    per user  2/s, burst 10
     * holds-ip     /api/bookings/holds/**    per IP   20/min, burst 20 (load-test identities: per subject)
     * orders       /api/bookings/orders/**   per user  2/s, burst 10
     * tickets      GET notification tickets  per user  1/s, burst 5
     * </pre>
     *
     * The login, signup and email-verification buckets match their path whatever the method, so nothing on those
     * paths reaches auth unmetered through {@code auth-public} (auth answers anything but POST with 405; the gateway
     * denies anything but POST on the verify paths). Where a route has a per-user and a per-IP bucket, the per-user
     * one runs first and the response carries the per-IP bucket's {@code X-RateLimit-*} headers.
     *
     * <p>The buckets marked CLOSED answer 503 when the limiter cannot reach Redis, so an outage cannot lift a limit
     * on password guessing, code guessing or email sending. The others fail open (the stock limiter's behaviour),
     * but only anonymous reads really keep working through an outage: every request with a bearer token also needs
     * Redis for the token denylist ({@code TokenDenylist}), which fails closed with 503.
     *
     * <p>{@code POST /api/auth/logout} is not a route here: {@code LogoutConfig}'s RouterFunction answers it in the
     * gateway, and WebFlux's RouterFunctionMapping (order -1) matches before any of these routes (order 1).
     */
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder, RedisRateLimiter limiter, ProblemWriter problems,
            ClientAddress clients,
            @Value("${surge.routes.auth-uri:lb://auth-service}") String auth,
            @Value("${surge.routes.catalog-uri:lb://catalog-service}") String catalog,
            @Value("${surge.routes.booking-uri:lb://booking-service}") String booking,
            @Value("${surge.routes.notification-uri:lb://notification-service}") String notification,
            @Value("${surge.routes.payment-uri:lb://payment-service}") String payment) {

        KeyResolver perUser = KeyResolvers.user();
        KeyResolver perIp = KeyResolvers.clientIp(clients);
        RateLimitFilter loginLimit = new RateLimitFilter(limiter, problems, "auth-login", perIp,
                Limit.perSecond(5, 10), OnLimiterFailure.REFUSE);
        RateLimitFilter signupLimit = new RateLimitFilter(limiter, problems, "auth-signup", perIp,
                Limit.perMinute(3), OnLimiterFailure.REFUSE);
        RateLimitFilter verifyLimit = new RateLimitFilter(limiter, problems, "verify", perUser,
                Limit.perMinute(10), OnLimiterFailure.REFUSE);
        RateLimitFilter verifyIpLimit = new RateLimitFilter(limiter, problems, "verify-ip", perIp,
                Limit.perMinute(30), OnLimiterFailure.REFUSE);
        RateLimitFilter resendLimit = new RateLimitFilter(limiter, problems, "resend", perUser,
                Limit.perMinute(3), OnLimiterFailure.REFUSE);
        RateLimitFilter resendIpLimit = new RateLimitFilter(limiter, problems, "resend-ip", perIp,
                Limit.perMinute(5), OnLimiterFailure.REFUSE);
        RateLimitFilter meLimit = new RateLimitFilter(limiter, problems, "auth-me", perUser, Limit.perSecond(2, 4));
        RateLimitFilter catalogLimit = new RateLimitFilter(limiter, problems, "catalog", perIp,
                Limit.perSecond(10, 20));
        RateLimitFilter eventsLimit = new RateLimitFilter(limiter, problems, "events", perIp, Limit.perSecond(20, 40));
        RateLimitFilter streamLimit = new RateLimitFilter(limiter, problems, "stream-open", perIp,
                Limit.perSecond(2, 20));
        RateLimitFilter holdsLimit = new RateLimitFilter(limiter, problems, "holds", perUser, Limit.perSecond(2, 10));
        RateLimitFilter holdsIpLimit = new RateLimitFilter(limiter, problems, "holds-ip",
                KeyResolvers.clientIpExceptLoadtest(clients), Limit.perMinute(20));
        RateLimitFilter ordersLimit = new RateLimitFilter(limiter, problems, "orders", perUser,
                Limit.perSecond(2, 10));
        RateLimitFilter ticketsLimit = new RateLimitFilter(limiter, problems, "tickets", perUser,
                Limit.perSecond(1, 5));

        // Every route names its target's spring.application.name (AUDIENCE): the gateway signs each request's
        // X-Surge-Identity for exactly that service. RouteAudiences.requireAll refuses to start without it.
        return RouteAudiences.requireAll(builder.routes()
                // --- ADMIN (the owning service) ---
                .route("auth-admin", r -> r.path("/api/auth/admin/**")
                        .metadata(AUDIENCE, RouteAudiences.AUTH).uri(auth))
                .route("payments-admin", r -> r.path("/api/payments/admin/**")
                        .metadata(AUDIENCE, RouteAudiences.PAYMENT).uri(payment))
                .route("bookings-admin", r -> r.path("/api/bookings/admin/**")
                        .metadata(AUDIENCE, RouteAudiences.BOOKING).uri(booking))
                .route("catalog-admin", r -> r.path("/api/catalog/admin/**")
                        .metadata(AUDIENCE, RouteAudiences.CATALOG).uri(catalog))
                .route("notifications-admin", r -> r.path("/api/notifications/admin/**")
                        .metadata(AUDIENCE, RouteAudiences.NOTIFICATION).uri(notification))
                // --- auth ---
                .route("auth-me", r -> r.path("/api/auth/me")
                        .filters(f -> f.filter(meLimit))
                        .metadata(AUDIENCE, RouteAudiences.AUTH).uri(auth))
                .route("auth-login", r -> r.path("/api/auth/login")
                        .filters(f -> f.filter(loginLimit))
                        .metadata(AUDIENCE, RouteAudiences.AUTH).uri(auth))
                .route("auth-signup", r -> r.path("/api/auth/signup")
                        .filters(f -> f.filter(signupLimit))
                        .metadata(AUDIENCE, RouteAudiences.AUTH).uri(auth))
                .route("auth-verify", r -> r.path("/api/auth/verify")
                        .filters(f -> f.filter(verifyLimit).filter(verifyIpLimit))
                        .metadata(AUDIENCE, RouteAudiences.AUTH).uri(auth))
                .route("auth-verify-resend", r -> r.path("/api/auth/verify/resend")
                        .filters(f -> f.filter(resendLimit).filter(resendIpLimit))
                        .metadata(AUDIENCE, RouteAudiences.AUTH).uri(auth))
                // everything else under /api/auth (the JWKS); SecurityConfig decides what may get here at all
                .route("auth-public", r -> r.path("/api/auth/**")
                        .metadata(AUDIENCE, RouteAudiences.AUTH).uri(auth))
                // --- catalog (public reads) ---
                .route("catalog", r -> r.method(HttpMethod.GET).and().path("/api/catalog/**")
                        .filters(f -> f.filter(catalogLimit).circuitBreaker(c -> c.setName(CATALOG_BREAKER)
                                .setFallbackUri("forward:/fallback/catalog")))
                        .metadata(AUDIENCE, RouteAudiences.CATALOG).uri(catalog))
                // --- booking: the SSE stream has no response timeout; other public event reads use the default ---
                .route("booking-events-stream", r -> r.method(HttpMethod.GET).and()
                        .path("/api/bookings/events/*/stream")
                        .filters(f -> f.filter(streamLimit))
                        .metadata(RESPONSE_TIMEOUT, -1)
                        .metadata(AUDIENCE, RouteAudiences.BOOKING)
                        .uri(booking))
                .route("booking-events", r -> r.method(HttpMethod.GET).and().path("/api/bookings/events/**")
                        .filters(f -> f.filter(eventsLimit))
                        .metadata(AUDIENCE, RouteAudiences.BOOKING).uri(booking))
                .route("booking-holds", r -> r.path("/api/bookings/holds/**")
                        .filters(f -> f.filter(holdsLimit).filter(holdsIpLimit)
                                .circuitBreaker(c -> c.setName(BOOKING_BREAKER)
                                        .setFallbackUri("forward:/fallback/booking")))
                        .metadata(AUDIENCE, RouteAudiences.BOOKING).uri(booking))
                .route("booking-orders", r -> r.path("/api/bookings/orders/**")
                        .filters(f -> f.filter(ordersLimit)
                                .circuitBreaker(c -> c.setName(BOOKING_BREAKER)
                                        .setFallbackUri("forward:/fallback/booking")))
                        .metadata(AUDIENCE, RouteAudiences.BOOKING).uri(booking))
                // --- notification ---
                .route("notification-tickets", r -> r.method(HttpMethod.GET).and()
                        .path("/api/notifications/tickets/**")
                        .filters(f -> f.filter(ticketsLimit))
                        .metadata(AUDIENCE, RouteAudiences.NOTIFICATION).uri(notification))
                .build());
    }

    /**
     * Resilience4j's default time limiter is 1 s, far below what a checkout may take (payment client alone allows 2 s plus
     * a retry), so the limiter is lifted to {@code surge.gateway.breaker-timeout}. Only connection failures and timeouts open the
     * breaker; a downstream 4xx/5xx is passed through untouched.
     *
     * <p>A call its bulkhead refused ({@link BulkheadConfig}) is ignored, neither a failure nor a success: it never
     * reached the service. Counted as a failure, one burst past the bulkhead would open the breaker and turn away every
     * hold and checkout for 10 s, including the calls the bulkhead had room for.
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
                        .ignoreExceptions(BulkheadFullException.class)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(timeout).build())
                .build());
    }
}
