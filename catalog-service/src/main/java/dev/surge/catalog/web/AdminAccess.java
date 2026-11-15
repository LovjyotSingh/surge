package dev.surge.catalog.web;

import dev.surge.catalog.error.ForbiddenException;

/**
 * The in-service admin check for catalog's {@code /api/catalog/admin/**} endpoints, kept in one place so Task S2
 * can swap the header read for a verified identity token without touching the controllers.
 */
public final class AdminAccess {
    private AdminAccess() {
    }

    /** The role list must contain the exact token {@code ADMIN} (case-sensitive, whitespace around tokens ignored). */
    public static void requireAdmin(String roles) {
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
