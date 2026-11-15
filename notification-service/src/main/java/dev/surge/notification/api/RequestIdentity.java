package dev.surge.notification.api;

import dev.surge.notification.error.ForbiddenException;
import dev.surge.notification.error.MissingUserException;
import java.util.regex.Pattern;

/** The identity headers the gateway sets, checked the same way everywhere. */
public final class RequestIdentity {

    private static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private RequestIdentity() {
    }

    /** A well-formed {@code X-User-Id}, or 401 missing-user. */
    public static String requireUser(String userId) {
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw new MissingUserException();
        }
        return userId;
    }

    /** The exact token ADMIN in a comma-separated list; "SUPERADMIN" or "admin" do not count. Otherwise 403. */
    public static void requireAdmin(String roles) {
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
