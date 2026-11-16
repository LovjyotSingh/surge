package dev.surge.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import dev.surge.auth.api.TokenResponse;
import dev.surge.auth.api.UserView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** What a user token carries (SEC-18): audience surge-api, a unique jti, 30 minutes; load-test tokens 15. */
class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00.700Z");
    private static final RSAKey KEY = generate();

    private final TokenService tokens = new TokenService(KEY, Clock.fixed(NOW, ZoneOffset.UTC));

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("k1").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Only for tokens issued with the real clock: the decoder checks exp against it. */
    private static Jwt decode(String token) throws Exception {
        return NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build().decode(token);
    }

    private static UserView user() {
        return new UserView("u-7", "ada@example.com", "Ada", List.of("USER"));
    }

    @Test
    void userTokensAreForTheSurgeApiWithAUniqueIdAndLiveThirtyMinutes() throws Exception {
        TokenResponse response = new TokenService(KEY, Clock.systemUTC()).issue(user());
        Jwt jwt = decode(response.token());
        assertThat(jwt.getAudience()).containsExactly(TokenService.AUDIENCE).containsExactly("surge-api");
        assertThat(jwt.getId()).matches("^[0-9a-f-]{36}$");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(TokenService.LIFETIME).isEqualTo(Duration.ofMinutes(30));
        assertThat(response.expiresAt()).isEqualTo(jwt.getExpiresAt());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("surge-auth");
        assertThat(jwt.hasClaim("lt")).isFalse();
    }

    @Test
    void everyTokenHasItsOwnJti() throws Exception {
        TokenService live = new TokenService(KEY, Clock.systemUTC());
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            ids.add(decode(live.issue(user()).token()).getId());
        }
        assertThat(ids).hasSize(20);
    }

    @Test
    void loadTestTokensKeepFifteenMinutesAndTheirFlag() throws Exception {
        Jwt jwt = decode(new TokenService(KEY, Clock.systemUTC()).issueLoadTest(
                new UserView("lt-run-1", "lt-run-1@loadtest.local", "lt-run-1", List.of("USER"))).token());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.<Boolean>getClaim("lt")).isTrue();
        assertThat(jwt.getAudience()).containsExactly("surge-api");
        assertThat(jwt.getId()).isNotBlank();
    }

    @Test
    void issuedAtIsWholeSeconds() {
        TokenResponse response = tokens.issue(user());
        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-11-16T10:30:00Z"));
    }
}
