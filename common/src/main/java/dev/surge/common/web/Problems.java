package dev.surge.common.web;

import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Factory for RFC 9457 problem details with Surge conventions. */
public final class Problems {

    private static final String TYPE_BASE = "https://surge.dev/problems/";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + slug));
        pd.setTitle(title);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        pd.setProperty("requestId", requestId != null ? requestId : "");
        return pd;
    }
}
