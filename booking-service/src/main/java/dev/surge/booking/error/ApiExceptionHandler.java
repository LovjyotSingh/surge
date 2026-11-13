package dev.surge.booking.error;

import dev.surge.common.web.Problems;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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

    /** Helper for later handlers: always responds with application/problem+json. */
    static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
