package dev.surge.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Propagates or generates a request ID, exposing it via MDC and the response header. An incoming id is kept only when
 * it matches {@link #SAFE_ID} (the gateway's rule); anything else, such as {@code a b<script>} or a line break, is
 * replaced by a fresh UUID, so a caller that reaches a service directly cannot forge log lines (SEC-26).
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    static final int MAX_LENGTH = 64;
    /** The same pattern the gateway applies at the edge. */
    public static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1," + MAX_LENGTH + "}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
