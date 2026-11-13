package dev.surge.booking.payment;

public interface PaymentGateway {

    PaymentResult charge(PaymentRequest request);

    void refund(String paymentId);
}
