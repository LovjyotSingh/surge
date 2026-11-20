package dev.surge.gateway.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4jBulkheadProvider;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadConfigurationBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The bulkhead inside each circuit breaker ({@link RouteConfig#BOOKING_BREAKER}, {@link RouteConfig#CATALOG_BREAKER}):
 * how many calls the gateway lets wait on that service at once. Each breaker has its own limit,
 * {@code surge.gateway.bulkhead.*} in application.yml (env {@code SURGE_GATEWAY_BULKHEAD_BOOKING} and so on), where
 * the numbers are explained. One call more is refused at once and gets the breaker's 503 fallback.
 *
 * <p>With {@code resilience4j-bulkhead} on the classpath, Spring Cloud CircuitBreaker wraps each reactive circuit
 * breaker in a Resilience4j semaphore bulkhead. Its library default, 25 concurrent calls, was never a choice: in the
 * first live 10,000-buyer run 29 holds reached booking and every other buyer got the fallback. A limit as large as
 * booking's whole connection pool (an earlier fix) protected nothing, so each service now gets its own share.
 *
 * <p>A refused call never waits for a permit ({@code maxWaitDuration} zero). The reactive bulkhead takes its permit
 * synchronously when the call is subscribed ({@code MonoBulkhead} calls {@code SemaphoreBulkhead.tryAcquirePermission},
 * which is {@code Semaphore.tryAcquire(maxWaitDuration)}), on a Netty event loop or parallel scheduler thread: any wait
 * would stall every other request on that thread. Refusals do not count as failures of the service either: the
 * breaker ignores {@code BulkheadFullException} ({@link RouteConfig}), so a burst cannot open it.
 */
@Configuration(proxyBeanMethods = false)
public class BulkheadConfig {

    @Bean
    Customizer<ReactiveResilience4jBulkheadProvider> bulkheads(
            @Value("${surge.gateway.bulkhead.booking:5000}") int booking,
            @Value("${surge.gateway.bulkhead.catalog:300}") int catalog,
            @Value("${surge.gateway.bulkhead.others:100}") int others) {
        return bulkheads(Map.of(RouteConfig.BOOKING_BREAKER, booking, RouteConfig.CATALOG_BREAKER, catalog), others);
    }

    /** {@code limits} by breaker name; any breaker not named there gets {@code others}. */
    static Customizer<ReactiveResilience4jBulkheadProvider> bulkheads(Map<String, Integer> limits, int others) {
        limits.forEach(BulkheadConfig::requirePositive);
        requirePositive("others", others);
        return provider -> {
            provider.configureDefault(id -> new Resilience4jBulkheadConfigurationBuilder()
                    .bulkheadConfig(semaphore(others)).build());
            limits.forEach((id, max) -> provider.configure(builder -> builder.bulkheadConfig(semaphore(max)), id));
        };
    }

    private static io.github.resilience4j.bulkhead.BulkheadConfig semaphore(int maxConcurrentCalls) {
        return io.github.resilience4j.bulkhead.BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(Duration.ZERO)
                .build();
    }

    private static void requirePositive(String breaker, int max) {
        if (max < 1) {
            throw new IllegalArgumentException("surge.gateway.bulkhead." + breaker + " must be at least 1, was " + max);
        }
    }
}
