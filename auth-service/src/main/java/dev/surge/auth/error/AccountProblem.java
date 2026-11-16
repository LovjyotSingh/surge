package dev.surge.auth.error;

import dev.surge.common.web.Problems;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The account-protection problems (S5): the sign-in throttle and the email verification answers. An
 * {@link ErrorResponseException}, so {@code ApiExceptionHandler}'s base class renders it with its headers
 * ({@code Retry-After}). No message ever contains an email, a code or a password.
 */
public final class AccountProblem extends ErrorResponseException {

    private AccountProblem(HttpStatus status, String slug, String title, String detail) {
        super(status, Problems.of(status, slug, title, detail), null);
    }

    private AccountProblem retryAfter(Duration wait) {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        return this;
    }

    /**
     * Too many failed sign-ins for one account. Identical for registered and unknown emails: same status, body and
     * header rules, so the answer reveals nothing about the account.
     */
    public static AccountProblem loginThrottled(Duration wait) {
        return new AccountProblem(HttpStatus.TOO_MANY_REQUESTS, "login-throttled", "Too many failed sign-ins",
                "Too many failed sign-in attempts for this account. Try again later.").retryAfter(wait);
    }

    public static AccountProblem codeInvalid(int attemptsLeft) {
        AccountProblem problem = new AccountProblem(HttpStatus.BAD_REQUEST, "verification-code-invalid", "Wrong code",
                "That code is not right.");
        problem.getBody().setProperty("attemptsLeft", attemptsLeft);
        return problem;
    }

    public static AccountProblem codeLocked() {
        return new AccountProblem(HttpStatus.BAD_REQUEST, "verification-code-locked", "Code locked",
                "Too many wrong codes. Request a new code.");
    }

    public static AccountProblem codeExpired() {
        return new AccountProblem(HttpStatus.BAD_REQUEST, "verification-code-expired", "Code expired",
                "This code has expired or was already used. Request a new code.");
    }

    public static AccountProblem alreadyVerified() {
        return new AccountProblem(HttpStatus.CONFLICT, "already-verified", "Email already verified",
                "This account's email address is already verified.");
    }

    public static AccountProblem resendThrottled(Duration wait) {
        return new AccountProblem(HttpStatus.TOO_MANY_REQUESTS, "resend-throttled", "Too many codes requested",
                "Wait a little before asking for another code.").retryAfter(wait);
    }

    public static AccountProblem mailUnavailable() {
        return new AccountProblem(HttpStatus.SERVICE_UNAVAILABLE, "mail-unavailable", "Email unavailable",
                "The verification email could not be sent. Try again in a minute.");
    }

    /** The problem body (never null for these). */
    public ProblemDetail problem() {
        return getBody();
    }
}
