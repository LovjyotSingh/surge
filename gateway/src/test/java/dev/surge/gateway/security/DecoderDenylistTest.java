package dev.surge.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * The gateway's decoder with the real {@link TokenDenylist} over a mocked Redis (no Docker): a denylist that cannot
 * answer must fail the request closed (AuthenticationServiceException, which the gateway renders as 503), never let a
 * possibly revoked token through.
 */
class DecoderDenylistTest {

    private static final RSAKey KEY = generate();

    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final ReactiveJwtDecoder decoder = new SecurityConfig().jwtDecoder(
            new JwksCache(() -> Mono.just(new JWKSet(KEY.toPublicJWK())), Clock.systemUTC()),
            new TokenDenylist(redis), "surge-auth", false);

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("k1").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token() throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(),
                new JWTClaimsSet.Builder().issuer("surge-auth").subject("u-1").audience(List.of("surge-api"))
                        .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(600))).claim("roles", List.of("USER")).build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private Jwt decode(String token) {
        return decoder.decode(token).block(Duration.ofSeconds(5));
    }

    @Test
    void aTokenThatIsNotRevokedPasses() throws Exception {
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
        assertThat(decode(token()).getSubject()).isEqualTo("u-1");
    }

    @Test
    void aRevokedTokenIsABadToken() throws Exception {
        when(redis.hasKey(anyString())).thenReturn(Mono.just(true));
        assertThatThrownBy(() -> decode(token())).isInstanceOf(BadJwtException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void aFailingDenylistFailsClosed() throws Exception {
        when(redis.hasKey(anyString())).thenReturn(Mono.error(new RedisConnectionFailureException("redis is down")));
        assertThatThrownBy(() -> decode(token())).isInstanceOf(AuthenticationServiceException.class);
    }

    @Test
    void aHangingDenylistFailsClosedAfterItsTimeout() throws Exception {
        when(redis.hasKey(anyString())).thenReturn(Mono.never());
        long start = System.nanoTime();
        assertThatThrownBy(() -> decode(token())).isInstanceOf(AuthenticationServiceException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void anEmptyAnswerCountsAsNotRevoked() throws Exception {
        when(redis.hasKey(anyString())).thenReturn(Mono.empty());
        assertThat(decode(token()).getSubject()).isEqualTo("u-1");
    }
}
