package dev.surge.auth.api;

import dev.surge.auth.user.User;
import java.util.List;

public final class UserViews {
    public static final String LOADTEST_DOMAIN = "@loadtest.local";

    private UserViews() {
    }

    public static UserView of(User u) {
        return new UserView(u.publicId(), u.getEmail(), u.getDisplayName(), List.of(u.getRole().name()));
    }

    public static UserView synthetic(String id) {
        return new UserView(id, id + LOADTEST_DOMAIN, id, List.of("USER"));
    }
}
