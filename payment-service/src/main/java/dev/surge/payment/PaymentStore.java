package dev.surge.payment;

import dev.surge.payment.error.NotRefundableException;
import dev.surge.payment.error.PaymentNotFoundException;
import dev.surge.payment.error.ProviderErrorException;
import dev.surge.payment.error.ReferenceConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/** In-memory fake provider. A result is stored once per reference and replayed for every later call. */
@Component
public class PaymentStore {
    private final ConcurrentHashMap<String, Payment> byReference = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Payment> byId = new ConcurrentHashMap<>();
    private final ChaosSettings chaos;
    private final RandomGenerator random;
    private final MeterRegistry meters;

    public PaymentStore(ChaosSettings chaos, RandomGenerator random, MeterRegistry meters) {
        this.chaos = chaos;
        this.random = random;
        this.meters = meters;
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
}
