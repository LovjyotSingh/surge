package dev.surge.auth.jwt;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import dev.surge.auth.api.TokenResponse;
import dev.surge.auth.api.UserView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Issues the user JWTs the gateway verifies (RS256, {@code kid} of the current key). Every token is for audience
 * {@value #AUDIENCE} only and has its own {@code jti}, which logout puts on the gateway's denylist (SEC-18).
 */
@Service
public class TokenService {
    public static final String ISSUER = "surge-auth";
    /** The gateway accepts a user token only when its {@code aud} contains this. */
    public static final String AUDIENCE = "surge-api";
    public static final Duration LIFETIME = Duration.ofMinutes(30);
    /** Load-test tokens are short-lived and flagged so the gateway can refuse them in production. */
    public static final Duration LOADTEST_LIFETIME = Duration.ofMinutes(15);
    public static final String LOADTEST_CLAIM = "lt";

    private final JwtEncoder encoder;
    private final Clock clock;

    TokenService(RSAKey signingKey, Clock clock) {
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey)));
        this.clock = clock;
    }

    public TokenResponse issue(UserView user) {
        return issue(user, LIFETIME, false);
    }

    /** A synthetic load-test token: claim {@code lt: true} and a 15 minute lifetime. */
    public TokenResponse issueLoadTest(UserView user) {
        return issue(user, LOADTEST_LIFETIME, true);
    }

    private TokenResponse issue(UserView user, Duration lifetime, boolean loadTest) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(lifetime);
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .id(UUID.randomUUID().toString())
                .subject(user.id())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("email", user.email())
                .claim("roles", user.roles());
        if (loadTest) {
            builder.claim(LOADTEST_CLAIM, true);
        }
        JwtClaimsSet claims = builder
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(token, expiresAt, user);
    }
}
