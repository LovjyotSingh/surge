package dev.surge.gateway.config;

import java.time.Duration;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4jBulkheadProvider;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadConfigurationBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The bulkhead around every circuit breaker ({@code booking}, {@code catalog} in {@link RouteConfig}).
 *
 * <p>With {@code resilience4j-bulkhead} on the classpath, Spring Cloud CircuitBreaker wraps each reactive circuit
 * breaker in a Resilience4j semaphore bulkhead. Its library default, 25 concurrent calls and no waiting, was never a
 * choice: in the first live 10,000-buyer run, 29 holds reached booking and every other buyer got the "booking is
 * temporarily unavailable" fallback, because a sale's buyers all arrive in the same second.
 *
 * <p>Load is bounded elsewhere: per-user and per-IP rate limits here, the breaker's 10 s time limiter, and booking's
 * own caps (Tomcat's {@code max-connections} of {@value #MAX_CONCURRENT_CALLS}, holds per user, seat streams). The
 * bulkhead only stops runaway concurrency beyond what booking can hold. It never waits for a permit: in this reactive
 * gateway that would block a Netty event loop thread.
 */
@Configuration(proxyBeanMethods = false)
public class BulkheadConfig {

    /** booking-service's {@code server.tomcat.max-connections}: more concurrent calls could not be served anyway. */
    public static final int MAX_CONCURRENT_CALLS = 20_000;

    @Bean
    Customizer<ReactiveResilience4jBulkheadProvider> bulkheadDefaults() {
        return provider -> provider.configureDefault(id -> new Resilience4jBulkheadConfigurationBuilder()
                .bulkheadConfig(io.github.resilience4j.bulkhead.BulkheadConfig.custom()
                        .maxConcurrentCalls(MAX_CONCURRENT_CALLS)
                        .maxWaitDuration(Duration.ZERO)
                        .build())
                .build());
    }
}
