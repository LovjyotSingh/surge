package dev.surge.notification.error;

import dev.surge.common.web.Problems;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error becomes an RFC 9457 problem ({@code application/problem+json}) with a Surge {@code type} and the
 * request's {@code requestId}. The service has almost no HTTP surface (actuator only); this keeps the shape uniform.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Last resort: a bug or an infrastructure failure. Logged in full, but the client only sees a generic text. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        ProblemDetail pd = Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "Something went wrong on our side. Quote the requestId if you report it.");
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
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
}
