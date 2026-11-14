package dev.surge.auth.api;

import dev.surge.auth.error.UserNotFoundException;
import dev.surge.auth.user.UserRepository;
import org.springframework.stereotype.Service;

/** Resolves a public user id (u-N from the database, lt-* synthetic) to a view. */
@Service
public class UserLookup {
    private final UserRepository users;

    UserLookup(UserRepository users) {
        this.users = users;
    }

    public UserView find(String id) {
        if (id.startsWith("lt-")) {
            return UserViews.synthetic(id);
        }
        if (id.startsWith("u-")) {
            long n;
            try {
                n = Long.parseLong(id.substring(2));
            } catch (NumberFormatException e) {
                throw new UserNotFoundException();
            }
            return users.findById(n).map(UserViews::of).orElseThrow(UserNotFoundException::new);
        }
        throw new UserNotFoundException();
    }
}
