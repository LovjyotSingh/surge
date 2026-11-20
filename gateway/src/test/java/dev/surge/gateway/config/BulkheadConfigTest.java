package dev.surge.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4jBulkheadProvider;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Each circuit breaker's bulkhead has its own limit and refuses the call past it at once ({@link BulkheadConfig}), and
 * those refusals never open the breaker ({@link RouteConfig#breakerDefaults}). The limits the running gateway gets
 * from application.yml are checked in {@code CircuitBreakerBulkheadTest}.
 */
class BulkheadConfigTest {

    private static final String BOOKING = RouteConfig.BOOKING_BREAKER;
    private static final String CATALOG = RouteConfig.CATALOG_BREAKER;

    @Test
    void eachBreakerRefusesTheCallPastItsOwnLimit() {
        ReactiveResilience4jBulkheadProvider bulkheads = bulkheads(Map.of(BOOKING, 3, CATALOG, 2), 1);

        assertLimit(bulkheads, BOOKING, 3);
        assertLimit(bulkheads, CATALOG, 2);
        assertLimit(bulkheads, "a-breaker-with-no-limit-of-its-own", 1);
    }

    @Test
    void aLimitBelowOneIsRefusedAtStartup() {
        assertThatThrownBy(() -> BulkheadConfig.bulkheads(Map.of(BOOKING, 0), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bulkhead.booking");
        assertThatThrownBy(() -> BulkheadConfig.bulkheads(Map.of(BOOKING, 1), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bulkhead.others");
    }

    @Test
    void refusalsByAFullBulkheadNeverOpenTheBreaker() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        ReactiveCircuitBreaker breaker = bookingBreaker(registry, 1);
        Disposable inFlight = breaker.run(Mono.never(), t -> Mono.just("fallback")).subscribe();

        // Far past the breaker's minimum of 10 calls and 50 % failure rate, had refusals counted as failures.
        for (int i = 0; i < 30; i++) {
            assertThat(breaker.run(Mono.just("booked"), t -> Mono.just("fallback")).block()).isEqualTo("fallback");
        }

        assertThat(registry.circuitBreaker(BOOKING).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        inFlight.dispose();
        assertThat(breaker.run(Mono.just("booked"), t -> Mono.just("fallback")).block()).isEqualTo("booked");
    }

    @Test
    void aServiceThatCannotBeReachedStillOpensTheBreaker() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        ReactiveCircuitBreaker breaker = bookingBreaker(registry, 5000);

        for (int i = 0; i < 10; i++) {
            breaker.run(Mono.error(new ConnectException("refused")), t -> Mono.just("fallback")).block();
        }

        assertThat(registry.circuitBreaker(BOOKING).getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private static ReactiveResilience4jBulkheadProvider bulkheads(Map<String, Integer> limits, int others) {
        ReactiveResilience4jBulkheadProvider provider = new ReactiveResilience4jBulkheadProvider(
                BulkheadRegistry.ofDefaults());
        BulkheadConfig.bulkheads(limits, others).customize(provider);
        return provider;
    }

    /** The gateway's booking breaker (RouteConfig's defaults) around a bulkhead of {@code limit}. */
    private static ReactiveCircuitBreaker bookingBreaker(CircuitBreakerRegistry registry, int limit) {
        ReactiveResilience4JCircuitBreakerFactory factory = new ReactiveResilience4JCircuitBreakerFactory(registry,
                TimeLimiterRegistry.ofDefaults(), bulkheads(Map.of(BOOKING, limit), 1),
                new Resilience4JConfigurationProperties());
        new RouteConfig().breakerDefaults(Duration.ofSeconds(10)).customize(factory);
        return factory.create(BOOKING);
    }

    /** {@code limit} calls in flight are let through; the next one is refused while subscribing, without waiting. */
    private static void assertLimit(ReactiveResilience4jBulkheadProvider bulkheads, String breaker, int limit) {
        List<Disposable> inFlight = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            AtomicReference<Throwable> error = new AtomicReference<>();
            inFlight.add(bulkheads.decorateMono(breaker, Map.of(), Mono.never()).subscribe(v -> { }, error::set));
            assertThat(error.get()).as("call %d of %d through %s", i + 1, limit, breaker).isNull();
        }

        AtomicReference<Throwable> refused = new AtomicReference<>();
        bulkheads.decorateMono(breaker, Map.of(), Mono.never()).subscribe(v -> { }, refused::set);
        assertThat(refused.get()).as("call %d through %s", limit + 1, breaker)
                .isInstanceOf(BulkheadFullException.class);
        assertThat(bulkheads.getBulkheadRegistry().bulkhead(breaker).getBulkheadConfig().getMaxWaitDuration())
                .isZero();

        // A finished call frees its permit.
        inFlight.removeFirst().dispose();
        assertThat(bulkheads.decorateMono(breaker, Map.of(), Mono.just("ok")).block()).isEqualTo("ok");
        inFlight.forEach(Disposable::dispose);
    }
}
