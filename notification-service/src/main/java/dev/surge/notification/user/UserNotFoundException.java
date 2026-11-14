package dev.surge.notification.user;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String userId) {
        super("User " + userId + " not found in auth-service");
    }
}
