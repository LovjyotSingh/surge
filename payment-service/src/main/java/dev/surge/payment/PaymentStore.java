package dev.surge.payment;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.surge.payment.error.NotRefundableException;
import dev.surge.payment.error.PaymentNotFoundException;
import dev.surge.payment.error.ProviderErrorException;
import dev.surge.payment.error.ReferenceConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory fake provider. A result is stored once per reference and replayed for every later call.
 *
 * <p>SEC-29: both maps are bounded, so no caller can grow this service's memory without limit. Each holds at most
 * {@code surge.payment-store.max-payments} payments (default {@value #DEFAULT_MAX_PAYMENTS}, roughly 25 MB) and forgets
 * one {@code surge.payment-store.retention} after it was made (default 24 h). booking needs a stored result only for
 * minutes: its retries, refunds and the reconciler's lookups happen within the hold lifetime plus a minute, and a
 * checkout replayed later is answered from booking's own order. The 24 h retention matches booking's idempotency
 * window with a wide margin. Past either bound a reference is forgotten: a later charge with it is a new charge, and
 * its status or refund is 404. A new payment always survives at least its first few hundred successors (Caffeine's
 * admission window), far longer than any booking retry.
 */
@Component
public class PaymentStore {

    static final long DEFAULT_MAX_PAYMENTS = 50_000;
    static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private final Cache<String, Payment> referenceCache;
    private final Cache<String, Payment> idCache;
    private final ConcurrentMap<String, Payment> byReference;
    private final ConcurrentMap<String, Payment> byId;
    private final ChaosSettings chaos;
    private final RandomGenerator random;
    private final MeterRegistry meters;

    @Autowired
    public PaymentStore(ChaosSettings chaos, RandomGenerator random, MeterRegistry meters,
                        @Value("${surge.payment-store.max-payments:" + DEFAULT_MAX_PAYMENTS + "}") long maxPayments,
                        @Value("${surge.payment-store.retention:PT24H}") Duration retention) {
        this(chaos, random, meters, maxPayments, retention, Ticker.systemTicker());
    }

    PaymentStore(ChaosSettings chaos, RandomGenerator random, MeterRegistry meters, long maxPayments,
                 Duration retention, Ticker ticker) {
        if (maxPayments < 1 || retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("surge.payment-store.max-payments and retention must be positive");
        }
        this.referenceCache = bounded(maxPayments, retention, ticker);
        this.idCache = bounded(maxPayments, retention, ticker);
        this.byReference = referenceCache.asMap();
        this.byId = idCache.asMap();
        this.chaos = chaos;
        this.random = random;
        this.meters = meters;
    }

    private static Cache<String, Payment> bounded(long maxPayments, Duration retention, Ticker ticker) {
        return Caffeine.newBuilder().maximumSize(maxPayments).expireAfterWrite(retention).ticker(ticker).build();
    }

    public ChargeResult charge(ChargeRequest req) {
        Payment existing = byReference.get(req.reference());
        if (existing != null) {
            return replay(existing, req);
        }
        Chaos c = chaos.get();
        if (c.latencyMs() > 0) {
            try {
                Thread.sleep(c.latencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderErrorException("interrupted");
            }
        }
        AtomicBoolean created = new AtomicBoolean();
        // computeIfAbsent on Caffeine's map view is atomic per key, like ConcurrentHashMap's
        Payment payment = byReference.computeIfAbsent(req.reference(), ref -> {
            if (random.nextDouble() < c.errorRate()) {
                meters.counter("payments_total", "result", "error").increment();
                // thrown from computeIfAbsent: nothing is stored, so a retry can succeed
                throw new ProviderErrorException("simulated provider error");
            }
            boolean approved = !(random.nextDouble() < c.failureRate());
            Payment p = new Payment(ref, "pay_" + UUID.randomUUID(), req.userId(), req.amountInr(), approved,
                    approved ? null : "card_declined");
            byId.put(p.paymentId, p);
            created.set(true);
            return p;
        });
        if (created.get()) {
            meters.counter("payments_total", "result", payment.approved ? "approved" : "declined").increment();
            return payment.result();
        }
        return replay(payment, req);
    }

    private ChargeResult replay(Payment payment, ChargeRequest req) {
        if (!payment.sameRequest(req)) {
            throw new ReferenceConflictException(req.reference());
        }
        if (payment.refunded.get()) {
            // A refunded charge must never read as approved again: a same-reference retry would confirm an order on it.
            return new ChargeResult(false, payment.paymentId, "refunded");
        }
        return payment.result();
    }

    public void refund(String paymentId) {
        Payment p = byId.get(paymentId);
        if (p == null) {
            throw new PaymentNotFoundException(paymentId);
        }
        if (!p.approved) {
            throw new NotRefundableException(paymentId);
        }
        if (p.refunded.compareAndSet(false, true)) {
            meters.counter("refunds_total").increment();
        }
    }

    /** Lookup for reconciliation; unknown reference is a 404. */
    public PaymentStatus find(String reference) {
        Payment p = byReference.get(reference);
        if (p == null) {
            throw new PaymentNotFoundException(reference);
        }
        return new PaymentStatus(p.approved, p.paymentId, p.declineReason, p.refunded.get());
    }

    /** Runs pending evictions now (tests). */
    void cleanUp() {
        referenceCache.cleanUp();
        idCache.cleanUp();
    }

    /** Payments currently held, by reference (tests). */
    long size() {
        return referenceCache.estimatedSize();
    }
}
