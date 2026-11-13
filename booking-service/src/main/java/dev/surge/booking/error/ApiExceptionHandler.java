package dev.surge.booking.error;

import dev.surge.common.web.Problems;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** Helper for later handlers: always responds with application/problem+json. */
    static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
