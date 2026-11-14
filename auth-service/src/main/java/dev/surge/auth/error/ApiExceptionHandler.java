package dev.surge.auth.error;

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
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error becomes an RFC 9457 problem ({@code application/problem+json}) with a Surge {@code type} and the
 * request's {@code requestId}. Nothing here ever echoes a password or hash; unexpected errors show a generic text.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ProblemDetail> badCredentials(BadCredentialsException e) {
        return problem(Problems.of(HttpStatus.UNAUTHORIZED, "bad-credentials", "Bad credentials", e.getMessage()));
    }

    @ExceptionHandler(EmailTakenException.class)
    ResponseEntity<ProblemDetail> emailTaken(EmailTakenException e) {
        return problem(Problems.of(HttpStatus.CONFLICT, "email-taken", "Email already registered", e.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<ProblemDetail> userNotFound(UserNotFoundException e) {
        return problem(Problems.of(HttpStatus.NOT_FOUND, "user-not-found", "User not found", e.getMessage()));
    }

    @ExceptionHandler(MissingUserException.class)
    ResponseEntity<ProblemDetail> missingUser(MissingUserException e) {
        return problem(Problems.of(HttpStatus.UNAUTHORIZED, "missing-user", "Missing or invalid user",
                e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ProblemDetail> forbidden(ForbiddenException e) {
        return problem(Problems.of(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", e.getMessage()));
    }

    /** Last resort: a bug or an infrastructure failure. Logged in full, but the client only sees a generic text. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "Something went wrong on our side. Quote the requestId if you report it."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        // field name + message only: the rejected value (possibly a password) is never echoed
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
                "Malformed request body", "Request body is missing or could not be parsed"), headers, status,
                request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException e,
                                                                     HttpHeaders headers, HttpStatusCode status,
                                                                     WebRequest request) {
        return handleExceptionInternal(e, Problems.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type",
                "Unsupported media type", "Content type is not supported; use application/json"),
                headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed",
                "A request value has the wrong type");
        return handleExceptionInternal(e, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

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

    static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
