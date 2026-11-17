package dev.surge.auth.verify;

import dev.surge.auth.api.TokenResponse;
import dev.surge.auth.error.AccountProblem;
import dev.surge.auth.error.UserNotFoundException;
import dev.surge.auth.jwt.TokenService;
import dev.surge.common.security.CurrentIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's email verification (S5): {@code POST /api/auth/verify {code}} answers a fresh token with
 * {@code ev: true} (the old one stays valid but unverified until it expires or is logged out), and
 * {@code POST /api/auth/verify/resend} emails a new code (202). Only a gateway user reaches them (the chain's rule in
 * {@code AccessRulesConfig}); the methods check again.
 */
@RestController
@RequestMapping("/api/auth/verify")
class VerificationController {

    record VerifyRequest(@NotNull @Pattern(regexp = "^[0-9]{6}$", message = "must be the 6-digit code") String code) {
        @Override
        public String toString() {
            return "VerifyRequest[code=***]";
        }
    }

    private final EmailVerificationService verification;
    private final TokenService tokens;

    VerificationController(EmailVerificationService verification, TokenService tokens) {
        this.verification = verification;
        this.tokens = tokens;
    }

    @PostMapping
    TokenResponse verify(@Valid @RequestBody VerifyRequest req) {
        return tokens.issue(verification.verify(accountOf(CurrentIdentity.requireUser()), req.code()));
    }

    @PostMapping("/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> resend() {
        verification.resend(accountOf(CurrentIdentity.requireUser()));
        return Map.of("sent", true, "expiresInSeconds", EmailVerification.CODE_LIFETIME.toSeconds());
    }

    /** {@code u-42} → 42. Load-test identities ({@code lt-*}) have no mailbox and count as verified. */
    private static long accountOf(String userId) {
        if (userId.startsWith("lt-")) {
            throw AccountProblem.alreadyVerified();
        }
        if (!userId.startsWith("u-")) {
            throw new UserNotFoundException();
        }
        try {
            return Long.parseLong(userId.substring(2));
        } catch (NumberFormatException e) {
            throw new UserNotFoundException();
        }
    }
}
