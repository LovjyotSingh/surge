package dev.surge.common.web;

import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Factory for RFC 9457 problem details with Surge conventions. */
public final class Problems {

    public static final String TYPE_BASE = "https://surge.dev/problems/";
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + slug));
        pd.setTitle(title);
        pd.setProperty("requestId", currentRequestId());
        return pd;
    }

    /**
     * Brings a problem built elsewhere (for example by Spring MVC itself) in line with Surge conventions: a
     * {@code type} under {@link #TYPE_BASE} when it has none, and the current {@code requestId}. A type that is
     * already set is kept, so custom slugs are never overwritten.
     */
    public static ProblemDetail conform(ProblemDetail pd, String slug) {
        if (pd.getType() == null || ABOUT_BLANK.equals(pd.getType())) {
            pd.setType(URI.create(TYPE_BASE + slug));
        }
        if (pd.getProperties() == null || !pd.getProperties().containsKey("requestId")) {
            pd.setProperty("requestId", currentRequestId());
        }
        return pd;
    }

    private static String currentRequestId() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId != null ? requestId : "";
    }
}
