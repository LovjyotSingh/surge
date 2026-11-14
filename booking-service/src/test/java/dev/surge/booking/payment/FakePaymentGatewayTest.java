package dev.surge.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The fake behaves like payment-service where booking can tell: ids on declines, replay by reference, refunds. */
class FakePaymentGatewayTest {

    private static final PaymentRequest REQUEST = new PaymentRequest("u1:k1", "u1", new BigDecimal("10.00"));

    @Test
    void declineCarriesAPaymentId() {
        PaymentResult result = new FakePaymentGateway(1.0).charge(REQUEST);

        assertThat(result.approved()).isFalse();
        assertThat(result.declineReason()).isEqualTo("card_declined");
        assertThat(result.paymentId()).isNotNull();
    }

    @Test
    void sameReferenceReplaysAndARefundedPaymentIsNeverApprovedAgain() {
        FakePaymentGateway fake = new FakePaymentGateway(0.0);
        PaymentResult first = fake.charge(REQUEST);
        assertThat(fake.charge(REQUEST).paymentId()).isEqualTo(first.paymentId());
        assertThat(fake.status("u1:k1")).hasValueSatisfying(s -> assertThat(s.refunded()).isFalse());

        fake.refund(first.paymentId());

        PaymentResult replay = fake.charge(REQUEST);
        assertThat(replay.approved()).isFalse();
        assertThat(replay.declineReason()).isEqualTo("refunded");
        assertThat(fake.status("u1:k1")).hasValueSatisfying(s -> assertThat(s.refunded()).isTrue());
        assertThat(fake.status("nope")).isEmpty();
    }
}
