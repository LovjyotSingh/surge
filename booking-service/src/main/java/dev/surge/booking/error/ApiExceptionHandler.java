package dev.surge.booking.error;

import dev.surge.common.web.Problems;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EventNotFoundException.class)
    ResponseEntity<ProblemDetail> eventNotFound(EventNotFoundException e) {
        return problem(Problems.of(HttpStatus.NOT_FOUND, "event-not-found", "Event not found", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), String.valueOf(fe.getDefaultMessage())));
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "Request validation failed");
        pd.setProperty("errors", errors);
        return problem(pd);
    }

    @ExceptionHandler(MissingUserException.class)
    ResponseEntity<ProblemDetail> missingUser(MissingUserException e) {
        return problem(Problems.of(HttpStatus.UNAUTHORIZED, "missing-user", "Missing or invalid user", e.getMessage()));
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> malformed(HttpMessageNotReadableException e) {
        return problem(Problems.of(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request body",
                "Request body is missing or could not be parsed"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return problem(Problems.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type",
                "Unsupported media type", "Content type is not supported; use application/json"));
    }

    /** Helper for later handlers: always responds with application/problem+json. */
    static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
