package dev.surge.catalog.web;

import dev.surge.common.security.AdminAudit;
import dev.surge.common.security.CurrentIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Deny-by-default admin check for every {@code /api/catalog/admin/**} request, behind the identity chain's own
 * rule: no signed-in user is 401 missing-user, no exact {@code ADMIN} role from the gateway is 403 forbidden
 * ({@link CurrentIdentity#requireAdmin()}, through {@link AdminAudit}, which logs a refusal). It runs before argument
 * binding, so a non-admin with a malformed parameter never gets a 400 that describes the endpoint. No header is ever
 * read.
 */
public class AdminAccess implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AdminAudit.requireAdmin("catalog.admin");
        return true;
    }
}
