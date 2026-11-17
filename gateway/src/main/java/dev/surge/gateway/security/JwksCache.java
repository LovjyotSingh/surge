package dev.surge.gateway.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * auth-service's public keys (its JWKS) for verifying user JWTs, cached so that unknown {@code kid}s cannot make the
 * gateway fetch the key set on every request (SEC-18: a random {@code kid} per request was a 1:1 amplification).
 *
 * <ul>
 *   <li>A token whose {@code kid} is in the cached set is verified without any fetch.</li>
 *   <li>A {@code kid} miss refetches the set, but at most once per {@link #COOLDOWN} (30 s), counting every attempt,
 *       successful or not. Inside the cooldown a miss is simply "no such key": 401.</li>
 *   <li>A set older than {@link #MAX_AGE} is refreshed on the next lookup (same cooldown), so keys auth stops
 *       publishing (rotation) stop being accepted. If that refresh fails, the old set keeps working.</li>
 *   <li>Before the first successful fetch there is nothing to fall back to: attempts are spaced
 *       {@link #EMPTY_RETRY} apart and fail (503) in between.</li>
 *   <li>Concurrent lookups share one fetch in flight; the fetch always runs to completion (bounded by the fetcher's
 *       timeout) even if every request waiting for it is cancelled.</li>
 * </ul>
 */
public final class JwksCache {

    public static final Duration COOLDOWN = Duration.ofSeconds(30);
    public static final Duration EMPTY_RETRY = Duration.ofSeconds(2);
    public static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final Supplier<Mono<JWKSet>> fetcher;
    private final Clock clock;
    private final Object lock = new Object();

    private volatile JWKSet keys;
    private volatile Instant fetchedAt;
    /** Guarded by {@link #lock}. */
    private Instant lastAttempt;
    /** Guarded by {@link #lock}. */
    private Mono<JWKSet> inFlight;

    /**
     * @param fetcher one GET of the key set; must terminate (time out) on its own
     * @param clock   only for the cooldown and age bookkeeping
     */
    public JwksCache(Supplier<Mono<JWKSet>> fetcher, Clock clock) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The keys that may have signed {@code jwt} (by {@code kid}, key type and use); empty when none matches. */
    public Flux<JWK> keysFor(SignedJWT jwt) {
        JWKSelector selector = new JWKSelector(JWKMatcher.forJWSHeader(jwt.getHeader()));
        return current().flatMapMany(set -> {
            List<JWK> found = selector.select(set);
            if (!found.isEmpty()) {
                return Flux.fromIterable(found);
            }
            return refresh().flatMapMany(fresh -> Flux.fromIterable(selector.select(fresh)));
        });
    }

    private Mono<JWKSet> current() {
        JWKSet cached = keys;
        if (cached == null) {
            return refresh();
        }
        Instant at = fetchedAt;
        if (at != null && !clock.instant().isBefore(at.plus(MAX_AGE))) {
            return refresh().onErrorReturn(cached);
        }
        return Mono.just(cached);
    }

    /** A fetch when the cooldown allows one (or the one already in flight); otherwise the cached set. */
    private Mono<JWKSet> refresh() {
        synchronized (lock) {
            if (inFlight != null) {
                return inFlight;
            }
            JWKSet cached = keys;
            Instant now = clock.instant();
            Duration wait = cached == null ? EMPTY_RETRY : COOLDOWN;
            if (lastAttempt != null && now.isBefore(lastAttempt.plus(wait))) {
                return cached != null ? Mono.just(cached)
                        : Mono.error(new IllegalStateException("The token signing keys are not available yet"));
            }
            lastAttempt = now;
            Mono<JWKSet> fetch = Mono.defer(fetcher)
                    .switchIfEmpty(Mono.error(new IllegalStateException("The key set response was empty")))
                    .doOnNext(set -> {
                        keys = set;
                        fetchedAt = clock.instant();
                    })
                    .doFinally(signal -> {
                        synchronized (lock) {
                            inFlight = null;
                        }
                    })
                    .cache();
            inFlight = fetch;
            // Started here, not by the first caller: a cancelled request must never leave a fetch half-done.
            fetch.subscribe(set -> { }, error -> { });
            return fetch;
        }
    }
}
