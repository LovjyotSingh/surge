package dev.surge.auth.api;

import java.util.List;

/**
 * A user as auth shows it. {@code emailVerified} is false until the emailed code is entered (S5); the web uses it to
 * send a fresh account to the verify page, and the token carries it as {@code ev}.
 */
public record UserView(String id, String email, String displayName, List<String> roles, boolean emailVerified) {
}
