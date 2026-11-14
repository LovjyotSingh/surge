package dev.surge.auth.api;

import java.time.Instant;

public record TokenResponse(String token, Instant expiresAt, UserView user) {
}
