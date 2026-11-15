package dev.surge.common.security;

import static dev.surge.common.security.TestTokens.b64;
import static dev.surge.common.security.TestTokens.claims;
import static dev.surge.common.security.TestTokens.ecKey;
import static dev.surge.common.security.TestTokens.es256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The verifier on its own: no Spring, fixed clock, freshly generated keys. Every attack in the S2 brief is rejected. */
class InternalTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String AUDIENCE = "booking-service";

    private static final ECKey GATEWAY = ecKey("gateway");
    private static final ECKey BOOKING = ecKey("svc:booking");
    /** In the key set, but its issuer is not on the allowlist: only the allowlist can reject it. */
    private static final ECKey PAYMENT = ecKey("svc:payment");

    private static final JWKSet JWKS = new JWKSet(
            List.of(GATEWAY.toPublicJWK(), BOOKING.toPublicJWK(), PAYMENT.toPublicJWK()));

    private final InternalTokenVerifier verifier = new InternalTokenVerifier(JWKS, InternalTokens.KNOWN_ISSUERS,
            AUDIENCE, CLOCK);
    private final InternalTokenSigner gatewaySigner = new InternalTokenSigner(GATEWAY, "gateway", CLOCK);

    private static String gatewayToken(Instant iat, Instant exp) {
        return es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, iat, exp).build());
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidInternalTokenException.class);
    }

    // ---- accepted ---------------------------------------------------------------------------------------------

    @Test
    void validGatewayTokenIsAcceptedWithRolesMapped() {
        String token = gatewaySigner.sign(AUDIENCE, "u-42", List.of("USER", "ADMIN"), "req-7");

        InternalIdentity identity = verifier.verify(token);

        assertThat(identity.issuer()).isEqualTo("gateway");
        assertThat(identity.subject()).isEqualTo("u-42");
        assertThat(identity.roles()).containsExactlyInAnyOrder("USER", "ADMIN");
        assertThat(identity.requestId()).isEqualTo("req-7");
        assertThat(identity.audience()).isEqualTo(AUDIENCE);
        assertThat(identity.isAnonymous()).isFalse();
    }

    @Test
    void anonymousTokenHasNoSubjectAndNoRoles() {
        InternalIdentity identity = verifier.verify(gatewaySigner.sign(AUDIENCE, null, List.of(), null));

        assertThat(identity.subject()).isNull();
        assertThat(identity.isAnonymous()).isTrue();
        assertThat(identity.roles()).isEmpty();
        assertThat(identity.requestId()).isNull();
    }

    @Test
    void serviceTokenSignedWithItsOwnKeyIsAccepted() {
        var booking = new InternalTokenSigner(BOOKING, "svc:booking", CLOCK);
        InternalIdentity identity = verifier.verify(booking.sign(AUDIENCE, "svc:booking", List.of(), "req-8"));
        assertThat(identity.issuer()).isEqualTo("svc:booking");
        assertThat(identity.subject()).isEqualTo("svc:booking");
    }

    @Test
    void audienceMayBeAnArrayThatContainsThisService() {
        JWTClaimsSet claims = claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60))
                .audience(List.of("catalog-service", AUDIENCE)).build();
        assertThat(verifier.verify(es256(GATEWAY, "gateway", claims)).subject()).isEqualTo("u-1");
    }

    @Test
    void timeWindowEdgesAreInclusive() {
        // 60 s old with a 60 s lifetime, and issued 5 s "in the future" (clock skew): both still accepted
        assertThat(verifier.verify(gatewayToken(NOW.minusSeconds(60), NOW))).isNotNull();
        assertThat(verifier.verify(gatewayToken(NOW.plusSeconds(5), NOW.plusSeconds(65)))).isNotNull();
        // a short-lived token is accepted up to 5 s after its exp (skew)
        assertThat(verifier.verify(gatewayToken(NOW.minusSeconds(10), NOW.minusSeconds(5)))).isNotNull();
    }

    @Test
    void rotationKeepsTwoKeysPerIssuer() {
        ECKey next = ecKey("gateway");
        var rotated = new InternalTokenVerifier(new JWKSet(List.of(GATEWAY.toPublicJWK(), next.toPublicJWK())),
                Set.of("gateway"), AUDIENCE, CLOCK);
        assertThat(rotated.verify(new InternalTokenSigner(next, "gateway", CLOCK).sign(AUDIENCE, "u-1", List.of(),
                null)).subject()).isEqualTo("u-1");
        assertThat(rotated.verify(gatewaySigner.sign(AUDIENCE, "u-2", List.of(), null)).subject()).isEqualTo("u-2");
    }

    // ---- rejected: the brief's list ----------------------------------------------------------------------------

    @Test
    void rejectsAValidTokenForAnotherAudience() {
        assertRejected(gatewaySigner.sign("catalog-service", "u-1", List.of("USER"), null));
    }

    @Test
    void rejectsATokenSixtyOneSecondsOld() {
        assertRejected(gatewayToken(NOW.minusSeconds(61), NOW.minusSeconds(1)));
    }

    @Test
    void rejectsALifetimeLongerThanSixtySeconds() {
        assertRejected(gatewayToken(NOW, NOW.plusSeconds(61)));
        assertRejected(gatewayToken(NOW.minusSeconds(30), NOW.plusSeconds(3600)));
    }

    @Test
    void rejectsATokenIssuedInTheFutureBeyondTheSkew() {
        assertRejected(gatewayToken(NOW.plusSeconds(6), NOW.plusSeconds(60)));
    }

    @Test
    void rejectsAnExpiredShortLivedToken() {
        assertRejected(gatewayToken(NOW.minusSeconds(20), NOW.minusSeconds(6)));
    }

    @Test
    void rejectsAlgNone() {
        String header = b64("{\"alg\":\"none\",\"kid\":\"gateway\"}");
        String payload = b64(claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build().toString());
        assertRejected(header + "." + payload + ".");
        // and with the signature of a real token glued on
        String real = gatewayToken(NOW, NOW.plusSeconds(60));
        assertRejected(header + "." + payload + "." + real.substring(real.lastIndexOf('.') + 1));
    }

    @Test
    void rejectsHs256SignedWithThePublicKeyBytes() throws JOSEException {
        JWTClaimsSet claims = claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build();
        for (byte[] secret : List.of(GATEWAY.toECPublicKey().getEncoded(),
                GATEWAY.toPublicJWK().toJSONString().getBytes())) {
            String token = TestTokens.sign(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("gateway").build(), claims,
                    new MACSigner(secret));
            assertRejected(token);
        }
    }

    @Test
    void rejectsRs256() throws JOSEException {
        RSAKey rsa = new RSAKeyGenerator(2048).keyID("gateway").generate();
        String token = TestTokens.sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("gateway").build(),
                claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build(), new RSASSASigner(rsa));
        assertRejected(token);
    }

    @Test
    void rejectsOtherEcAlgorithmsAndCurves() throws JOSEException {
        ECKey p384 = new ECKeyGenerator(Curve.P_384).keyID("gateway").generate();
        String token = TestTokens.sign(new JWSHeader.Builder(JWSAlgorithm.ES384).keyID("gateway").build(),
                claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build(), TestTokens.signer(p384));
        assertRejected(token);
    }

    @Test
    void rejectsAKidThatIsNotTheIssuer() {
        // signed with the gateway key under the gateway kid, but claims to be booking
        assertRejected(es256(GATEWAY, "gateway", claims("svc:booking", AUDIENCE, NOW, NOW.plusSeconds(60)).build()));
        // no kid at all
        assertRejected(TestTokens.sign(new JWSHeader.Builder(JWSAlgorithm.ES256).build(),
                claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build(), TestTokens.signer(GATEWAY)));
    }

    @Test
    void rejectsABookingKeyTokenClaimingToBeTheGateway() {
        JWTClaimsSet claims = claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60))
                .claim("roles", List.of("USER", "ADMIN")).build();
        // honest kid: kid != iss
        assertRejected(es256(BOOKING, "svc:booking", claims));
        // lying kid: the gateway's public key does not verify booking's signature
        assertRejected(es256(BOOKING, "gateway", claims));
    }

    @Test
    void rejectsATamperedPayload() {
        String token = gatewaySigner.sign(AUDIENCE, "u-1", List.of("USER"), null);
        String[] parts = token.split("\\.");
        String forged = b64(claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60))
                .claim("roles", List.of("USER", "ADMIN")).build().toString());
        assertRejected(parts[0] + "." + forged + "." + parts[2]);
        // one flipped signature character
        char last = parts[2].charAt(5);
        assertRejected(parts[0] + "." + parts[1] + "." + parts[2].substring(0, 5) + (last == 'A' ? 'B' : 'A')
                + parts[2].substring(6));
    }

    @Test
    void rejectsAnUnknownIssuerEvenWithAKnownKey() {
        assertRejected(es256(PAYMENT, "svc:payment", claims("svc:payment", AUDIENCE, NOW, NOW.plusSeconds(60))
                .build()));
    }

    // ---- rejected: malformed --------------------------------------------------------------------------------------

    @Test
    void rejectsMissingOrMalformedClaims() {
        Instant exp = NOW.plusSeconds(60);
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).expirationTime(null).build()));
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).issueTime(null).build()));
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).jwtID(null).build()));
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).audience((String) null)
                .build()));
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).claim("roles", "ADMIN")
                .build()));
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp)
                .claim("roles", List.of("admin")).build()));
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).subject("u-1\r\nX: y")
                .build()));
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).claim("rid", 7).build()));
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp)
                .notBeforeTime(java.util.Date.from(NOW.plusSeconds(30))).build()));
    }

    @Test
    void rejectsGarbage() {
        for (String bad : List.of("", "garbage", "a.b.c", "a.b", "x".repeat(10_000))) {
            assertRejected(bad);
        }
        assertThatThrownBy(() -> verifier.verify(null)).isInstanceOf(InvalidInternalTokenException.class);
    }

    @Test
    void rejectsTokensThatCarryTheirOwnKey() {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("gateway").jwk(GATEWAY.toPublicJWK())
                .build();
        assertRejected(TestTokens.sign(header, claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build(),
                TestTokens.signer(GATEWAY)));
    }

    @Test
    void errorMessagesNeverEchoTheToken() {
        String token = gatewaySigner.sign("catalog-service", "u-1", List.of("USER"), null);
        assertThatThrownBy(() -> verifier.verify(token)).hasMessageNotContaining(token)
                .hasMessageNotContaining(token.split("\\.")[1]);
    }

    // ---- construction -------------------------------------------------------------------------------------------

    @Test
    void keySetMustHoldPublicP256KeysOnly() throws JOSEException {
        assertThatThrownBy(() -> new InternalTokenVerifier(new JWKSet(GATEWAY), Set.of("gateway"), AUDIENCE, CLOCK))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("public");
        ECKey p384 = new ECKeyGenerator(Curve.P_384).keyID("gateway").generate();
        assertThatThrownBy(() -> new InternalTokenVerifier(new JWKSet(p384.toPublicJWK()), Set.of("gateway"),
                AUDIENCE, CLOCK)).isInstanceOf(IllegalArgumentException.class);
        RSAKey rsa = new RSAKeyGenerator(2048).keyID("gateway").generate();
        assertThatThrownBy(() -> new InternalTokenVerifier(new JWKSet(rsa.toPublicJWK()), Set.of("gateway"),
                AUDIENCE, CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalTokenVerifier(new JWKSet(List.of()), Set.of("gateway"), AUDIENCE,
                CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalTokenVerifier(JWKS, Set.of("gateway"), " ", CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
