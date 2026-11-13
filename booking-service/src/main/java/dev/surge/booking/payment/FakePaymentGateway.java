package dev.surge.booking.payment;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stand-in payment provider: declines with probability {@code surge.payment.fake.failure-rate}. */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

    private final double failureRate;

    public FakePaymentGateway(@Value("${surge.payment.fake.failure-rate:0.0}") double failureRate) {
        this.failureRate = failureRate;
    }

    @Override
    public PaymentResult charge(PaymentRequest request) {
        RandomGenerator random = ThreadLocalRandom.current();
        if (random.nextDouble() < failureRate) {
            return new PaymentResult(false, null, "card_declined");
        }
        return new PaymentResult(true, "pay_" + UUID.randomUUID(), null);
    }

    @Override
    public void refund(String paymentId) {
        log.info("Refunded payment {}", paymentId);
    }
}
