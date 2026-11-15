package dev.surge.common.security;

import static dev.surge.common.security.TestTokens.ecKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InternalTokenSignerTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00.750Z");
    private static final ECKey GATEWAY = ecKey("gateway");

    private final InternalTokenSigner signer = new InternalTokenSigner(GATEWAY, "gateway",
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void signsEs256WithTheIssuerAsKidAndTheAgreedClaims() throws Exception {
        SignedJWT jwt = SignedJWT.parse(signer.sign("booking-service", "u-42", List.of("USER", "ADMIN"), "req-1"));

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("gateway");
        assertThat(jwt.verify(new ECDSAVerifier(GATEWAY.toPublicJWK()))).isTrue();

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("gateway");
        assertThat(claims.getAudience()).containsExactly("booking-service");
        assertThat(claims.getSubject()).isEqualTo("u-42");
        assertThat(claims.getStringListClaim("roles")).containsExactly("USER", "ADMIN");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(Instant.parse("2026-11-15T10:00:00Z"));
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(Instant.parse("2026-11-15T10:01:00Z"));
        assertThat(UUID.fromString(claims.getJWTID())).isNotNull();
        assertThat(claims.getStringClaim("rid")).isEqualTo("req-1");
    }

    @Test
    void anonymousTokensHaveNoSubjectAndAnEmptyRolesArray() throws Exception {
        JWTClaimsSet claims = SignedJWT.parse(signer.sign("catalog-service", null, List.of(), null))
                .getJWTClaimsSet();
        assertThat(claims.getSubject()).isNull();
        assertThat(claims.getStringListClaim("roles")).isEmpty();
        assertThat(claims.getClaim("rid")).isNull();
    }

    @Test
    void everyTokenHasItsOwnJti() throws Exception {
        String a = signer.sign("booking-service", "u-1", List.of("USER"), null);
        String b = signer.sign("booking-service", "u-1", List.of("USER"), null);
        assertThat(SignedJWT.parse(a).getJWTClaimsSet().getJWTID())
                .isNotEqualTo(SignedJWT.parse(b).getJWTClaimsSet().getJWTID());
    }

    @Test
    void anInvalidRequestIdIsLeftOut() throws Exception {
        for (String rid : List.of("", "x".repeat(65), "bad id", "a\r\nb")) {
            assertThat(SignedJWT.parse(signer.sign("booking-service", "u-1", List.of(), rid)).getJWTClaimsSet()
                    .getClaim("rid")).isNull();
        }
    }

    @Test
    void signIdentityRequiresTheSignersIssuer() {
        String token = signer.sign(new InternalIdentity("gateway", "u-1", Set.of("USER"), "r-1", "booking-service"));
        assertThat(token).isNotBlank();
        assertThatThrownBy(() -> signer.sign(new InternalIdentity("svc:booking", "u-1", Set.of(), null,
                "booking-service"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesMalformedInput() {
        assertThatThrownBy(() -> signer.sign(" ", "u-1", List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign("Booking Service", "u-1", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign("booking-service", "u-1\r\nX: y", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign("booking-service", "u-1", List.of("admin"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesKeysThatDoNotFitTheIssuer() {
        Clock clock = Clock.systemUTC();
        // public key only
        assertThatThrownBy(() -> new InternalTokenSigner(GATEWAY.toPublicJWK(), "gateway", clock))
                .isInstanceOf(IllegalArgumentException.class);
        // booking's key mounted into the gateway
        assertThatThrownBy(() -> new InternalTokenSigner(ecKey("svc:booking"), "gateway", clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kid");
        // not an issuer name
        assertThatThrownBy(() -> new InternalTokenSigner(ecKey("payment"), "payment", clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
