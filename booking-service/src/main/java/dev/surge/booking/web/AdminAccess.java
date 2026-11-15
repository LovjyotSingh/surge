package dev.surge.booking.web;

import dev.surge.common.security.CurrentIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Deny-by-default admin check for every {@code /api/bookings/admin/**} request, behind the identity chain's own
 * rule: no signed-in user is 401 missing-user, no exact {@code ADMIN} role from the gateway is 403 forbidden
 * ({@link CurrentIdentity#requireAdmin()}). It runs before argument binding, so a non-admin never learns about
 * parameters. No header is ever read.
 */
public class AdminAccess implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CurrentIdentity.requireAdmin();
        return true;
    }
}
