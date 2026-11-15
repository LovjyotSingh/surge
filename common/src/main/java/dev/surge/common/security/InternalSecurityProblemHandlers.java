package dev.surge.common.security;

import dev.surge.common.web.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 from the security chain as the same RFC 9457 problems the services' controllers produce
 * ({@code type}, {@code title}, {@code status}, {@code detail}, {@code instance}, {@code requestId}):
 * <ul>
 *   <li>no token, or a token that fails verification: 401 {@code unauthorized};</li>
 *   <li>a valid anonymous token (no user) on a rule that needs more: 401 {@code missing-user};</li>
 *   <li>a valid identity that a rule refuses: 403 {@code forbidden}.</li>
 * </ul>
 */
public final class InternalSecurityProblemHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(request, response, Problems.of(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized",
                "A valid internal identity token is required"));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        boolean anonymous = InternalIdentityAuthenticationConverter
                .identityOf(SecurityContextHolder.getContext().getAuthentication())
                .map(InternalIdentity::isAnonymous).orElse(true);
        ProblemDetail problem = anonymous
                ? Problems.of(HttpStatus.UNAUTHORIZED, "missing-user", "Missing or invalid user",
                        "A signed-in user is required")
                : Problems.of(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "You are not allowed to do this");
        write(request, response, problem);
    }

    private static void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        body.put("instance", request.getRequestURI());
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
