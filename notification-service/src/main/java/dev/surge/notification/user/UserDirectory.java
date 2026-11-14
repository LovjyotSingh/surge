package dev.surge.notification.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/** Looks up a user's email in auth-service. Synthetic load-test users (lt-*) never leave this process. */
public class UserDirectory {

    private static final String LOAD_TEST_PREFIX = "lt-";

    private final RestClient client;

    public UserDirectory(RestClient client) {
        this.client = client;
    }

    public String email(String userId) {
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
