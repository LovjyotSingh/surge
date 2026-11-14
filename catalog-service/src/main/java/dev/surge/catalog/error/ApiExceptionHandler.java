package dev.surge.catalog.error;

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
 * request's {@code requestId}. Errors that Spring MVC raises itself (unknown route, wrong method, missing part,
 * upload too large, ...) pass through {@link #createResponseEntity}, which stamps the same conventions on them.
 * Anything else is a 500 {@code internal-error} that never exposes the exception message.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EventNotFoundException.class)
    ResponseEntity<ProblemDetail> eventNotFound(EventNotFoundException e) {
        return problem(Problems.of(HttpStatus.NOT_FOUND, "event-not-found", "Event not found", e.getMessage()));
    }

    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<ProblemDetail> invalidParameter(InvalidParameterException e) {
        return problem(Problems.of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", e.getMessage()));
    }

    @ExceptionHandler(dev.surge.catalog.search.SearchUnavailableException.class)
    ResponseEntity<ProblemDetail> searchUnavailable(dev.surge.catalog.search.SearchUnavailableException e) {
        return problem(Problems.of(HttpStatus.SERVICE_UNAVAILABLE, "search-unavailable", "Search unavailable",
                "Search is temporarily unavailable. Try again shortly."));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ProblemDetail> forbidden(ForbiddenException e) {
        return problem(Problems.of(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", e.getMessage()));
    }

    @ExceptionHandler(InvalidImportException.class)
    ResponseEntity<ProblemDetail> invalidImport(InvalidImportException e) {
        return problem(Problems.of(HttpStatus.BAD_REQUEST, "invalid-import", "Invalid import file", e.getMessage()));
    }

    @ExceptionHandler(UploadTooLargeException.class)
    ResponseEntity<ProblemDetail> uploadTooLarge(UploadTooLargeException e) {
        return problem(Problems.of(HttpStatus.CONTENT_TOO_LARGE, "content-too-large", "Upload too large",
                e.getMessage()));
    }

    /** Last resort: a bug or an infrastructure failure. Logged in full, but the client only sees a generic text. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "Something went wrong on our side. Quote the requestId if you report it."));
    }

    // ---- Spring MVC exceptions, routed through ResponseEntityExceptionHandler ----

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
                "Unsupported media type", "Content type is not supported here"), headers, status, request);
    }

    /** {@code /events/abc}, {@code ?size=abc}: a value of the wrong type is a validation error. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = e instanceof MethodArgumentTypeMismatchException m ? m.getName() : e.getPropertyName();
        String detail = name == null ? "A request value has the wrong type" : "Invalid value for '" + name + "'";
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
        return handleExceptionInternal(e, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Every response built by {@link ResponseEntityExceptionHandler} ends here. */
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

    static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
