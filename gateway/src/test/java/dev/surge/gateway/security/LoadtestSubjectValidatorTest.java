package dev.surge.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/** The flag-on branch, without a Boot context (the shared gateway context runs with the default, flag off). */
class LoadtestSubjectValidatorTest {

    private static Jwt jwt(String sub, Object lt) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (lt != null) {
            b.claim("lt", lt);
        }
        return b.build();
    }

    @Test
    void loadtestSubjectsAndClaimAreRejectedByDefault() {
        OAuth2TokenValidator<Jwt> v = SecurityConfig.requireClaims(false);
        assertThat(v.validate(jwt("lt-x-1", null)).hasErrors()).isTrue();
        assertThat(v.validate(jwt("u-5", true)).hasErrors()).isTrue();
        assertThat(v.validate(jwt("lt-x-1", true)).hasErrors()).isTrue();
        assertThat(v.validate(jwt("u-5", null)).hasErrors()).isFalse();
        assertThat(v.validate(jwt("u-5", false)).hasErrors()).isFalse();
    }

    @Test
    void theFlagAllowsThem() {
        OAuth2TokenValidator<Jwt> v = SecurityConfig.requireClaims(true);
        assertThat(v.validate(jwt("lt-x-1", true)).hasErrors()).isFalse();
        assertThat(v.validate(jwt("lt-x-1", null)).hasErrors()).isFalse();
    }
}
