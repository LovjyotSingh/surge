package dev.surge.notification.user;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/** Looks up a user's email in auth-service. Synthetic load-test users (lt-*) never leave this process. */
public class UserDirectory {

    private static final String LOAD_TEST_PREFIX = "lt-";
    /** Same shape as auth-service's user id. */
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final RestClient client;

    public UserDirectory(RestClient client) {
        this.client = client;
    }

    public String email(String userId) {
        // Validate first: the id goes into a URL path, where "." and ".." would be normalised away.
        if (userId == null || !VALID_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("invalid user id");
        }
        if (userId.startsWith(LOAD_TEST_PREFIX)) {
            return userId + "@loadtest.local";
        }
        UserView user = client.get().uri("/internal/users/{id}", userId).retrieve()
                .onStatus(s -> s.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                    throw new UserNotFoundException(userId);
                })
                .body(UserView.class);
        if (user == null || user.email() == null) {
            throw new UserNotFoundException(userId);
        }
        return user.email();
    }
}
