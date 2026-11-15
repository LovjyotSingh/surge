package dev.surge.catalog.web;

import dev.surge.catalog.error.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Deny-by-default admin check for every {@code /api/catalog/admin/**} request. It runs before argument binding,
 * so a non-admin with a malformed parameter gets 403, never a 400 that describes the endpoint. Task S2 replaces the
 * header read in {@link #requireAdmin} with a verified identity token.
 */
public class AdminAccess implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        requireAdmin(request.getHeader("X-User-Roles"));
        return true;
    }

    /** The role list must contain the exact token {@code ADMIN} (case-sensitive, whitespace around tokens ignored). */
    static void requireAdmin(String roles) {
        if (roles != null) {
            for (String role : roles.split(",")) {
                if (role.trim().equals("ADMIN")) {
                    return;
                }
            }
        }
        throw new ForbiddenException("Administrator access is required");
    }
}
