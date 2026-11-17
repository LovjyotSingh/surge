package dev.surge.booking.error;

import dev.surge.booking.payment.PaymentInDoubtException;
import dev.surge.booking.payment.PaymentUnavailableException;
import dev.surge.common.web.Problems;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 problem ({@code application/problem+json}) with a Surge {@code type} and the
 * request's {@code requestId}. Surge's own exceptions have explicit handlers below. Errors that Spring MVC raises
 * itself (unknown route, wrong method, bad path variable, ...) are handled by {@link ResponseEntityExceptionHandler}
 * and pass through {@link #createResponseEntity}, which stamps the same conventions on them. Anything else is a
 * 500 {@code internal-error} that never exposes the exception message. 401 {@code missing-user} and 403
 * {@code forbidden} come from {@code CurrentIdentity} (an {@code ErrorResponseException}, rendered by the base class)
 * or, before any controller runs, from the identity chain.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EventNotFoundException.class)
    ResponseEntity<ProblemDetail> eventNotFound(EventNotFoundException e) {
        return problem(Problems.of(HttpStatus.NOT_FOUND, "event-not-found", "Event not found", e.getMessage()));
    }

    @ExceptionHandler(InvalidSeatsException.class)
    ResponseEntity<ProblemDetail> invalidSeats(InvalidSeatsException e) {
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "invalid-seats", "Invalid seats", e.getMessage());
        pd.setProperty("seatIds", e.seatIds());
        return problem(pd);
    }

    @ExceptionHandler(SaleNotOpenException.class)
    ResponseEntity<ProblemDetail> saleNotOpen(SaleNotOpenException e) {
        ProblemDetail pd = Problems.of(HttpStatus.CONFLICT, "sale-not-open", "Sale not open", e.getMessage());
        pd.setProperty("saleOpensAt", e.saleOpensAt().toString());
        return problem(pd);
    }

    @ExceptionHandler(SeatsTakenException.class)
    ResponseEntity<ProblemDetail> seatsTaken(SeatsTakenException e) {
        ProblemDetail pd = Problems.of(HttpStatus.CONFLICT, "seat-taken", "Seat taken", e.getMessage());
        pd.setProperty("seatIds", e.seatIds());
        return problem(pd);
    }

    @ExceptionHandler(HoldExistsException.class)
    ResponseEntity<ProblemDetail> holdExists(HoldExistsException e) {
        ProblemDetail pd = Problems.of(HttpStatus.CONFLICT, "hold-exists", "Hold already exists", e.getMessage());
        pd.setProperty("holdId", e.holdId());
        return problem(pd);
    }

    @ExceptionHandler(TooManyHoldsException.class)
    ResponseEntity<ProblemDetail> tooManyHolds(TooManyHoldsException e) {
        ProblemDetail pd = Problems.of(HttpStatus.CONFLICT, "too-many-holds", "Too many active holds", e.getMessage());
        pd.setProperty("maxActiveHolds", e.max());
        return problem(pd);
    }

    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e) {
        ProblemDetail pd = Problems.of(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds())).body(pd);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ResponseEntity<ProblemDetail> invalidIdempotencyKey(InvalidIdempotencyKeyException e) {
        return problem(Problems.of(HttpStatus.BAD_REQUEST, "missing-idempotency-key", "Missing Idempotency-Key",
                e.getMessage()));
    }

    @ExceptionHandler(HoldNotFoundException.class)
    ResponseEntity<ProblemDetail> holdNotFound(HoldNotFoundException e) {
        return problem(Problems.of(HttpStatus.NOT_FOUND, "hold-not-found", "Hold not found", e.getMessage()));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ProblemDetail> orderNotFound(OrderNotFoundException e) {
        return problem(Problems.of(HttpStatus.NOT_FOUND, "order-not-found", "Order not found", e.getMessage()));
    }

    @ExceptionHandler(PaymentFailedException.class)
    ResponseEntity<ProblemDetail> paymentFailed(PaymentFailedException e) {
        ProblemDetail pd = Problems.of(HttpStatus.PAYMENT_REQUIRED, "payment-failed", "Payment failed", e.getMessage());
        pd.setProperty("reason", e.reason());
        return problem(pd);
    }

    /** The charge may or may not exist at the provider. It is recorded for reconciliation; retrying with the same key is safe. */
    @ExceptionHandler(PaymentInDoubtException.class)
    ResponseEntity<ProblemDetail> paymentInDoubt(PaymentInDoubtException e) {
        log.warn("Payment in doubt: {}", e.getMessage());
        ProblemDetail pd = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "payment-unavailable", "Payment unavailable",
                "Payment status unknown — retry with the same Idempotency-Key.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, "10").body(pd);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ProblemDetail> idempotencyKeyReused(IdempotencyKeyReusedException e) {
        return problem(Problems.of(HttpStatus.CONFLICT, "idempotency-key-reused", "Idempotency-Key reused",
                e.getMessage()));
    }

    /** The payment service is down or its circuit is open: nothing was charged, the hold is kept, try again shortly. */
    @ExceptionHandler(PaymentUnavailableException.class)
    ResponseEntity<ProblemDetail> paymentUnavailable(PaymentUnavailableException e) {
        log.warn("Payment unavailable: {}", e.getMessage());
        ProblemDetail pd = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "payment-unavailable", "Payment unavailable",
                "The payment service is not available right now. Your seats are still held; try again shortly.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, "10").body(pd);
    }

    /**
     * SEC-09: the per-event or total seat-stream cap is reached. A browser's EventSource does not reconnect after a
     * non-200 answer, so the seat map stays without live updates until the page is reloaded.
     */
    @ExceptionHandler(StreamCapacityException.class)
    ResponseEntity<ProblemDetail> streamCapacity(StreamCapacityException e) {
        ProblemDetail pd = Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "stream-capacity", "Too many live streams",
                e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, "30").body(pd);
    }

    @ExceptionHandler(SeatConflictException.class)
    ResponseEntity<ProblemDetail> seatConflict(SeatConflictException e) {
        return problem(Problems.of(HttpStatus.CONFLICT, "seat-taken", "Seat taken", e.getMessage()));
    }

    @ExceptionHandler(RequestInProgressException.class)
    ResponseEntity<ProblemDetail> requestInProgress(RequestInProgressException e) {
        return problem(Problems.of(HttpStatus.CONFLICT, "request-in-progress", "Request in progress", e.getMessage()));
    }

    @ExceptionHandler(ReportNotFoundException.class)
    ResponseEntity<ProblemDetail> reportNotFound(ReportNotFoundException e) {
        return problem(Problems.of(HttpStatus.NOT_FOUND, "report-not-found", "Report not found", e.getMessage()));
    }

    @ExceptionHandler(ReportTooLargeException.class)
    ResponseEntity<ProblemDetail> reportTooLarge(ReportTooLargeException e) {
        return problem(Problems.of(HttpStatus.CONTENT_TOO_LARGE, "report-too-large", "Report too large",
                e.getMessage()));
    }

    @ExceptionHandler(MalformedReportException.class)
    ResponseEntity<ProblemDetail> malformedReport(MalformedReportException e) {
        return problem(Problems.of(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request body",
                e.getMessage()));
    }

    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<ProblemDetail> invalidParameter(InvalidParameterException e) {
        return problem(Problems.of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", e.getMessage()));
    }

    /** Last resort: a bug or an infrastructure failure. Logged in full, but the client only sees a generic text. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "Something went wrong on our side. Quote the requestId if you report it."));
    }

    // ---- Spring MVC exceptions: same slugs as before, now routed through ResponseEntityExceptionHandler ----

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), String.valueOf(fe.getDefaultMessage())));
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "Request validation failed");
        pd.setProperty("errors", errors);
        return handleExceptionInternal(e, pd, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return handleExceptionInternal(e, Problems.of(HttpStatus.BAD_REQUEST, "malformed-request",
                "Malformed request body", "Request body is missing or could not be parsed"), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException e,
                                                                     HttpHeaders headers, HttpStatusCode status,
                                                                     WebRequest request) {
        return handleExceptionInternal(e, Problems.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type",
                "Unsupported media type", "Content type is not supported; use application/json"),
                headers, status, request);
    }

    /** {@code /events/abc}, {@code ?opensInSeconds=abc}: a value of the wrong type is a validation error. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = e instanceof MethodArgumentTypeMismatchException m ? m.getName() : e.getPropertyName();
        String detail = name == null ? "A request value has the wrong type" : "Invalid value for '" + name + "'";
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
        return handleExceptionInternal(e, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Every response built by {@link ResponseEntityExceptionHandler} ends here: give it a Surge {@code type} slug
     * (derived from the status) and the {@code requestId}, and send it as {@code application/problem+json}.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
                                                          WebRequest request) {
        ProblemDetail pd = body instanceof ProblemDetail p ? p : ProblemDetail.forStatus(status);
        Problems.conform(pd, slugFor(status));
        HttpHeaders out = new HttpHeaders();
        out.addAll(headers);
        out.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(pd, out, status);
    }

    /** 400 and 500 get Surge's own names; others follow the reason phrase, e.g. 405 becomes method-not-allowed. */
    static String slugFor(HttpStatusCode status) {
        if (status.value() == 400) {
            return "validation-failed";
        }
        if (status.value() == 500) {
            return "internal-error";
        }
        HttpStatus known = HttpStatus.resolve(status.value());
        if (known == null) {
            return "http-" + status.value();
        }
        return known.getReasonPhrase().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    /** Always responds with application/problem+json. */
    static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
