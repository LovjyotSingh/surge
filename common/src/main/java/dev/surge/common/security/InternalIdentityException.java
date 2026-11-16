package dev.surge.common.security;

import dev.surge.common.web.Problems;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * Thrown by {@link CurrentIdentity}. An {@link ErrorResponseException}, so every service's
 * {@code ResponseEntityExceptionHandler} renders it as the same problem it renders today: 401 {@code missing-user} or
 * 403 {@code forbidden}.
 */
public final class InternalIdentityException extends ErrorResponseException {

    private InternalIdentityException(HttpStatus status, String slug, String title, String detail) {
        super(status, Problems.of(status, slug, title, detail), null);
    }

    public static InternalIdentityException missingUser() {
        return new InternalIdentityException(HttpStatus.UNAUTHORIZED, "missing-user", "Missing or invalid user",
                "A signed-in user is required");
    }

    /** No verified identity at all (the same problem the chain answers for a missing or bad token). */
    public static InternalIdentityException unauthorized() {
        return new InternalIdentityException(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized",
                "A valid internal identity token is required");
    }

    public static InternalIdentityException forbidden() {
        return new InternalIdentityException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "You are not allowed to do this");
    }
}
