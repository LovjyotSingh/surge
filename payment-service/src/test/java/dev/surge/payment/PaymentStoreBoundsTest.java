package dev.surge.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.payment.error.PaymentNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * SEC-29: the fake provider's two maps are bounded (a size cap and a retention), so no caller, however many charges
 * it sends, can grow payment-service's memory without limit.
 */
class PaymentStoreBoundsTest {

    private final AtomicLong nanos = new AtomicLong();

    private PaymentStore store(long maxPayments, Duration retention) {
        return new PaymentStore(new ChaosSettings(), RandomGenerator.getDefault(), new SimpleMeterRegistry(),
                maxPayments, retention, nanos::get);
    }

    private static ChargeRequest charge(String reference) {
        return new ChargeRequest(reference, "u-1", new BigDecimal("100.00"));
    }

    private static boolean known(PaymentStore store, String reference) {
        try {
            store.find(reference);
            return true;
        } catch (PaymentNotFoundException e) {
            return false;
        }
    }

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        assertThat(PaymentStore.DEFAULT_MAX_PAYMENTS).isEqualTo(50_000);
        assertThat(PaymentStore.DEFAULT_RETENTION).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void neverHoldsMoreThanTheCap() {
        PaymentStore store = store(10, Duration.ofHours(24));
        for (int i = 0; i < 100; i++) {
            store.charge(charge("u-1:key-" + i));
        }
        store.cleanUp();

        assertThat(store.size()).isLessThanOrEqualTo(10);
        long kept = java.util.stream.IntStream.range(0, 100).filter(i -> known(store, "u-1:key-" + i)).count();
        assertThat(kept).isLessThanOrEqualTo(10);
    }

    @Test
    void forgetsAPaymentAfterTheRetention() {
        PaymentStore store = store(1000, Duration.ofHours(24));
        String paymentId = store.charge(charge("u-1:key-old")).paymentId();
        nanos.addAndGet(Duration.ofHours(23).toNanos());
        assertThat(known(store, "u-1:key-old")).isTrue();

        nanos.addAndGet(Duration.ofHours(1).plusSeconds(1).toNanos());
        store.cleanUp();
        assertThat(known(store, "u-1:key-old")).isFalse();
        assertThatThrownBy(() -> store.refund(paymentId)).isInstanceOf(PaymentNotFoundException.class);
        assertThat(store.size()).isZero();
    }

    @Test
    void aStoredResultIsStillReplayedWithinTheBounds() {
        PaymentStore store = store(1000, Duration.ofHours(24));
        ChargeResult first = store.charge(charge("u-1:key-same"));
        nanos.addAndGet(Duration.ofHours(1).toNanos());
        assertThat(store.charge(charge("u-1:key-same"))).isEqualTo(first);
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> store(0, Duration.ofHours(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store(10, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
