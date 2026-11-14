package dev.surge.booking.payment;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls payment-service over HTTP (surge.payment.mode=http). Every call is retry(circuit breaker(HTTP call)): each
 * attempt is counted by the breaker, and an open breaker is not retried. A decline is a normal 200 answer, never a
 * failure. Charges are idempotent by reference on the payment-service side, so retrying one is safe.
 */
public class HttpPaymentGateway implements PaymentGateway {

    private final RestClient client;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public HttpPaymentGateway(RestClient client, CircuitBreaker breaker, Retry retry) {
        this.client = client;
        this.breaker = breaker;
        this.retry = retry;
    }

    @Override
    public PaymentResult charge(PaymentRequest request) {
        PaymentResult result = protect("charge", () -> client.post().uri("/api/payments").body(request)
                .retrieve().body(PaymentResult.class));
        if (result == null) {
            throw new PaymentUnavailableException("payment service returned an empty body", null);
        }
        return result;
    }

    @Override
    public void refund(String paymentId) {
        protect("refund", () -> client.post().uri("/api/payments/{id}/refund", paymentId)
                .retrieve().toBodilessEntity());
    }

    private <T> T protect(String what, Supplier<T> call) {
        Supplier<T> guarded = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker, () -> timeoutAware(call)));
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            throw new PaymentUnavailableException("payment circuit is open: " + what + " not attempted", e);
        } catch (ResourceAccessException | HttpServerErrorException | PaymentTimeoutException e) {
            throw new PaymentUnavailableException("payment service unavailable: " + what + " failed", e);
        }
        // A 4xx (HttpClientErrorException) is our own bug or a refund of an unknown/declined payment: it propagates.
    }

    /** Separates a read timeout (not retried) from other I/O errors (retried). A connect timeout is still retried. */
    private static <T> T timeoutAware(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            boolean readTimeout = (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException))
                    || (cause instanceof SocketTimeoutException);
            if (readTimeout) {
                throw new PaymentTimeoutException("payment service did not answer in time", e);
            }
            throw e;
        }
    }
}
