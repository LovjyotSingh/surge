package dev.surge.auth.api;

import dev.surge.auth.user.Role;
import dev.surge.auth.user.User;
import java.util.List;

public final class UserViews {
    public static final String LOADTEST_DOMAIN = "@loadtest.local";

    private UserViews() {
    }

    public static UserView of(User u) {
        return new UserView(u.publicId(), u.getEmail(), u.getDisplayName(), roles(u.getRole()), u.isEmailVerified());
    }

    /** ADMIN accounts are also USERs, so USER-only checks never lock an admin out. */
    private static List<String> roles(Role role) {
        return role == Role.ADMIN ? List.of("USER", "ADMIN") : List.of("USER");
    }

    /** A load-test identity: never emailed, so treated as verified (its tokens carry {@code ev: true}). */
    public static UserView synthetic(String id) {
        return new UserView(id, id + LOADTEST_DOMAIN, id, List.of("USER"), true);
    }
}
