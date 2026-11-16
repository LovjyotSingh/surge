package dev.surge.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.surge.gateway.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** SEC-18 cooldown: unknown kids must not turn into one key-set fetch each. No Spring context. */
class JwksCacheTest {

    private static final RSAKey K1 = key("k1");
    private static final RSAKey K2 = key("k2");

    private final MutableClock clock = new MutableClock();
    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicReference<Supplier<Mono<JWKSet>>> answer =
            new AtomicReference<>(() -> Mono.just(new JWKSet(List.of(K1.toPublicJWK(), K2.toPublicJWK()))));
    private final JwksCache cache = new JwksCache(() -> {
        fetches.incrementAndGet();
        return answer.get().get();
    }, clock);

    private static RSAKey key(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SignedJWT jwt(String kid) {
        return new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
                new JWTClaimsSet.Builder().subject("u-1").build());
    }

    private List<String> lookup(String kid) {
        return cache.keysFor(jwt(kid)).map(JWK::getKeyID).collectList().block(Duration.ofSeconds(5));
    }

    @Test
    void aHundredRandomKidsCauseAtMostOneFetchPer30Seconds() {
        assertThat(lookup("k1")).containsExactly("k1");
        assertThat(fetches).hasValue(1);

        clock.advance(Duration.ofSeconds(31));
        for (int i = 0; i < 100; i++) {
            assertThat(lookup(UUID.randomUUID().toString())).isEmpty();
        }
        assertThat(fetches).hasValue(2);

        // known keys keep working from the cache
        assertThat(lookup("k2")).containsExactly("k2");
        assertThat(fetches).hasValue(2);

        clock.advance(Duration.ofSeconds(29));
        assertThat(lookup("random")).isEmpty();
        assertThat(fetches).hasValue(2);
        clock.advance(Duration.ofSeconds(2));
        assertThat(lookup("random")).isEmpty();
        assertThat(fetches).hasValue(3);
    }

    @Test
    void concurrentMissesShareOneFetch() {
        answer.set(() -> Mono.delay(Duration.ofMillis(200)).map(x -> new JWKSet(K1.toPublicJWK())));
        List<List<String>> results = Flux.range(0, 50)
                .flatMap(i -> cache.keysFor(jwt("k1")).map(JWK::getKeyID).collectList(), 50)
                .collectList().block(Duration.ofSeconds(5));
        assertThat(results).hasSize(50).allSatisfy(r -> assertThat(r).containsExactly("k1"));
        assertThat(fetches).hasValue(1);
    }

    @Test
    void aNewKeyIsPickedUpOnTheFirstMissAfterTheCooldown() {
        answer.set(() -> Mono.just(new JWKSet(K1.toPublicJWK())));
        assertThat(lookup("k1")).containsExactly("k1");
        answer.set(() -> Mono.just(new JWKSet(List.of(K1.toPublicJWK(), K2.toPublicJWK()))));
        assertThat(lookup("k2")).as("inside the cooldown").isEmpty();
        clock.advance(Duration.ofSeconds(30));
        assertThat(lookup("k2")).containsExactly("k2");
        assertThat(fetches).hasValue(2);
    }

    @Test
    void anOldSetIsRefreshedSoRetiredKeysStopWorking() {
        assertThat(lookup("k1")).containsExactly("k1");
        answer.set(() -> Mono.just(new JWKSet(K2.toPublicJWK())));
        clock.advance(JwksCache.MAX_AGE);
        assertThat(lookup("k1")).isEmpty();
        assertThat(lookup("k2")).containsExactly("k2");
        assertThat(fetches).hasValue(2);
    }

    @Test
    void aFailedRefreshKeepsTheLastGoodKeys() {
        assertThat(lookup("k1")).containsExactly("k1");
        answer.set(() -> Mono.error(new IllegalStateException("auth is down")));
        clock.advance(JwksCache.MAX_AGE.plusSeconds(1));
        assertThat(lookup("k1")).containsExactly("k1");
        assertThat(fetches).hasValue(2);
        // the failed attempt counts towards the cooldown
        assertThat(lookup("k1")).containsExactly("k1");
        assertThat(fetches).hasValue(2);
    }

    @Test
    void withNothingCachedAttemptsAreSpacedAndFail() {
        answer.set(() -> Mono.error(new IllegalStateException("auth is down")));
        assertThatThrownBy(() -> lookup("k1")).hasMessageContaining("auth is down");
        assertThatThrownBy(() -> lookup("k1")).hasMessageContaining("not available yet");
        assertThat(fetches).hasValue(1);

        answer.set(() -> Mono.just(new JWKSet(K1.toPublicJWK())));
        clock.advance(JwksCache.EMPTY_RETRY);
        assertThat(lookup("k1")).containsExactly("k1");
        assertThat(fetches).hasValue(2);
    }
}
