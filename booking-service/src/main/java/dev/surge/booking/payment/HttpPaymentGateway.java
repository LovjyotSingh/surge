package dev.surge.booking.payment;

import dev.surge.booking.error.IdempotencyKeyReusedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls payment-service over HTTP (surge.payment.mode=http). Every call is retry(circuit breaker(HTTP call)): each
 * attempt is counted by the breaker, and an open breaker is not retried. A decline is a normal 200 answer, never a
 * failure. Charges are idempotent by reference on the payment-service side, so retrying one is safe.
 *
 * <p>When a failure leaves the outcome of a charge unknown (a read timeout, or an I/O error after the connection was
 * made) the exception is a {@link PaymentInDoubtException}; when nothing was sent (open circuit, failed connect, no
 * instance) it is a plain {@link PaymentUnavailableException}.
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
        PaymentResult result = protect("charge", () -> {
            try {
                return client.post().uri("/api/payments").body(request).retrieve().body(PaymentResult.class);
            } catch (HttpClientErrorException.Conflict e) {
                if (e.getResponseBodyAsString().contains("reference-conflict")) {
                    throw new IdempotencyKeyReusedException();
                }
                throw e;
            }
        });
        if (result == null) {
            throw new PaymentInDoubtException("payment service returned an empty body", null);
        }
        return result;
    }

    @Override
    public void refund(String paymentId) {
        protect("refund", () -> client.post().uri("/api/payments/{id}/refund", paymentId)
                .retrieve().toBodilessEntity());
    }

    @Override
    public Optional<PaymentStatus> status(String reference) {
        return protect("status", () -> {
            try {
                return Optional.ofNullable(client.get().uri(b -> b.path("/api/payments")
                        .queryParam("reference", "{ref}").build(reference)).retrieve().body(PaymentStatus.class));
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.<PaymentStatus>empty(); // the provider never saw this reference
            }
        });
    }

    private <T> T protect(String what, Supplier<T> call) {
        AtomicBoolean maybeSent = new AtomicBoolean();
        Supplier<T> guarded = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker, () -> translate(call, maybeSent)));
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            throw new PaymentUnavailableException("payment circuit is open: " + what + " not attempted", e);
        } catch (ResourceAccessException | HttpServerErrorException | PaymentTimeoutException
                 | PaymentUnreachableException e) {
            String message = "payment service unavailable: " + what + " failed";
            if (maybeSent.get()) {
                throw new PaymentInDoubtException(message, e);
            }
            throw new PaymentUnavailableException(message, e);
        }
        // A 4xx (HttpClientErrorException) is our own bug or a refund of an unknown/declined payment: it propagates.
    }

    /**
     * Runs one attempt inside the breaker. Sorts failures into: read timeout (recorded, not retried), other I/O
     * (recorded, retried) and everything that is not an HTTP answer, such as the load balancer finding no instance
     * (recorded, not retried). Remembers in {@code maybeSent} when a request may have reached the provider.
     */
    private static <T> T translate(Supplier<T> call, AtomicBoolean maybeSent) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause();
            if (isReadTimeout(cause)) {
                maybeSent.set(true);
                throw new PaymentTimeoutException("payment service did not answer in time", e);
            }
            if (!isConnectFailure(e)) {
                maybeSent.set(true);
            }
            throw e;
        } catch (RestClientException e) {
            if (e instanceof HttpClientErrorException || e instanceof HttpServerErrorException) {
                throw e;
            }
            // e.g. a response that could not be decoded: the provider did answer, so the charge may exist.
            maybeSent.set(true);
            throw new PaymentUnreachableException("payment client failure: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            // BlockingLoadBalancerClient: "No instances available for payment-service"; nothing was sent.
            throw new PaymentUnreachableException("payment service not reachable: " + e.getMessage(), e);
        }
    }

    private static boolean isReadTimeout(Throwable cause) {
        return (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException))
                || cause instanceof SocketTimeoutException;
    }

    /** True when no byte of the request can have been sent: the connection was never made. */
    private static boolean isConnectFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                    || t instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }
}
