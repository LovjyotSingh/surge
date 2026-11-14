package dev.surge.booking.payment;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in payment provider (surge.payment.mode=fake, the default): declines with probability
 * {@code surge.payment.fake.failure-rate}. Like payment-service it remembers every charge by reference, so
 * {@link #status} works and a refunded payment is never replayed as approved.
 */
@Component
@ConditionalOnProperty(name = "surge.payment.mode", havingValue = "fake", matchIfMissing = true)
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

    private final double failureRate;
    private final Map<String, PaymentStatus> byReference = new ConcurrentHashMap<>();

    public FakePaymentGateway(@Value("${surge.payment.fake.failure-rate:0.0}") double failureRate) {
        this.failureRate = failureRate;
    }

    @Override
    public PaymentResult charge(PaymentRequest request) {
        RandomGenerator random = ThreadLocalRandom.current();
        boolean approved = !(random.nextDouble() < failureRate);
        PaymentStatus created = new PaymentStatus(approved, "pay_" + UUID.randomUUID(),
                approved ? null : "card_declined", false);
        PaymentStatus status = byReference.putIfAbsent(request.reference(), created);
        if (status == null) {
            status = created;
        }
        if (status.refunded()) {
            return new PaymentResult(false, status.paymentId(), "refunded");
        }
        return new PaymentResult(status.approved(), status.paymentId(), status.declineReason());
    }

    @Override
    public void refund(String paymentId) {
        byReference.replaceAll((ref, s) -> s.paymentId().equals(paymentId)
                ? new PaymentStatus(s.approved(), s.paymentId(), s.declineReason(), true) : s);
        log.info("Refunded payment {}", paymentId);
    }

    @Override
    public Optional<PaymentStatus> status(String reference) {
        return Optional.ofNullable(byReference.get(reference));
    }
}
