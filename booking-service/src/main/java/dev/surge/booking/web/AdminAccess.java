package dev.surge.booking.web;

import dev.surge.booking.error.ForbiddenException;
import dev.surge.booking.error.MissingUserException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * In-service admin check for every {@code /api/bookings/admin/**} request: no valid {@code X-User-Id} is 401, no
 * exact {@code ADMIN} token in {@code X-User-Roles} is 403 (same semantics as notification's RequestIdentity).
 *
 * <p>This is the single place that reads identity for admin routes. Task S2 replaces the header reads in
 * {@link #check} with a verified identity token; callers and the interceptor registration do not change.
 */
public class AdminAccess implements HandlerInterceptor {
    private static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    public boolean preHandle(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                             Object handler) {
        check(request.getHeader("X-User-Id"), request.getHeader("X-User-Roles"));
        return true;
    }

    static void check(String userId, String roles) {
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw new MissingUserException();
        }
        if (roles != null) {
            for (String role : roles.split(",")) {
                if (role.trim().equals("ADMIN")) {
                    return;
                }
            }
        }
        throw new ForbiddenException();
    }
}
