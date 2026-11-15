package dev.surge.common.security;

import static dev.surge.common.security.TestTokens.b64;
import static dev.surge.common.security.TestTokens.claims;
import static dev.surge.common.security.TestTokens.ecKey;
import static dev.surge.common.security.TestTokens.es256;
import static dev.surge.common.security.TestTokens.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
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
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jwt.JWTClaimsSet;
import dev.surge.common.security.InvalidInternalTokenException.Reason;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The verifier on its own: no Spring, fixed clock, freshly generated keys. Every attack in the S2 brief is rejected,
 * each for its own reason (checked by code, so a token that fails for an unrelated reason does not pass the test).
 */
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

    /** Accepts every real issuer, so the service-token rules are tested on their own. */
    private final InternalTokenVerifier verifier = new InternalTokenVerifier(JWKS, InternalTokens.KNOWN_ISSUERS,
            AUDIENCE, CLOCK);
    private final InternalTokenSigner gatewaySigner = new InternalTokenSigner(GATEWAY, "gateway", CLOCK);
    private final InternalTokenSigner bookingSigner = new InternalTokenSigner(BOOKING, "svc:booking", CLOCK);

    private static String gatewayToken(Instant iat, Instant exp) {
        return es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, iat, exp).build());
    }

    /** A token signed with booking's real key, claiming whatever the test sets on top of a service claim set. */
    private static JWTClaimsSet.Builder bookingClaims() {
        return claims("svc:booking", AUDIENCE, NOW, NOW.plusSeconds(60)).subject("svc:booking")
                .claim("roles", List.of());
    }

    private void assertRejected(String token, Reason reason) {
        assertRejected(verifier, token, reason);
    }

    private static void assertRejected(InternalTokenVerifier verifier, String token, Reason reason) {
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOfSatisfying(InvalidInternalTokenException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
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
        assertThat(identity.isUser()).isTrue();
    }

    @Test
    void anonymousTokenHasNoSubjectAndNoRoles() {
        InternalIdentity identity = verifier.verify(gatewaySigner.sign(AUDIENCE, null, List.of(), null));

        assertThat(identity.subject()).isNull();
        assertThat(identity.isAnonymous()).isTrue();
        assertThat(identity.isUser()).isFalse();
        assertThat(identity.roles()).isEmpty();
        assertThat(identity.requestId()).isNull();
    }

    @Test
    void serviceTokenSpeakingForItselfIsAccepted() {
        InternalIdentity identity = verifier.verify(bookingSigner.signForService(AUDIENCE, "req-8"));
        assertThat(identity.issuer()).isEqualTo("svc:booking");
        assertThat(identity.subject()).isEqualTo("svc:booking");
        assertThat(identity.roles()).isEmpty();
        assertThat(identity.isUser()).as("a service is never a user").isFalse();
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
        assertRejected(gatewaySigner.sign("catalog-service", "u-1", List.of("USER"), null), Reason.AUDIENCE);
    }

    @Test
    void rejectsATokenForSeveralAudiencesEvenIfOneIsThisService() {
        JWTClaimsSet claims = claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60))
                .audience(List.of("catalog-service", AUDIENCE)).build();
        assertRejected(es256(GATEWAY, "gateway", claims), Reason.AUDIENCE);
        claims = claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).audience(List.of(AUDIENCE, AUDIENCE)).build();
        assertRejected(es256(GATEWAY, "gateway", claims), Reason.AUDIENCE);
    }

    @Test
    void rejectsATokenSixtyOneSecondsOld() {
        assertRejected(gatewayToken(NOW.minusSeconds(61), NOW.minusSeconds(1)), Reason.EXPIRED);
    }

    @Test
    void rejectsALifetimeLongerThanSixtySecondsOrNone() {
        assertRejected(gatewayToken(NOW, NOW.plusSeconds(61)), Reason.LIFETIME);
        assertRejected(gatewayToken(NOW.minusSeconds(30), NOW.plusSeconds(3600)), Reason.LIFETIME);
        assertRejected(gatewayToken(NOW, NOW), Reason.LIFETIME);
        assertRejected(gatewayToken(NOW, NOW.minusSeconds(1)), Reason.LIFETIME);
    }

    @Test
    void rejectsATokenIssuedInTheFutureBeyondTheSkew() {
        assertRejected(gatewayToken(NOW.plusSeconds(6), NOW.plusSeconds(60)), Reason.NOT_YET_VALID);
    }

    @Test
    void rejectsAnExpiredShortLivedToken() {
        assertRejected(gatewayToken(NOW.minusSeconds(20), NOW.minusSeconds(6)), Reason.EXPIRED);
    }

    @Test
    void rejectsAlgNone() {
        String header = b64("{\"alg\":\"none\",\"typ\":\"surge-id+jwt\",\"kid\":\"gateway\"}");
        String payload = b64(claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build().toString());
        // not even a JWS: Nimbus refuses to parse "none" as a signed token
        assertRejected(header + "." + payload + ".", Reason.MALFORMED);
        String real = gatewayToken(NOW, NOW.plusSeconds(60));
        assertRejected(header + "." + payload + "." + real.substring(real.lastIndexOf('.') + 1), Reason.MALFORMED);
    }

    @Test
    void rejectsHs256SignedWithThePublicKeyBytes() throws JOSEException {
        JWTClaimsSet claims = claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build();
        for (byte[] secret : List.of(GATEWAY.toECPublicKey().getEncoded(),
                GATEWAY.toPublicJWK().toJSONString().getBytes())) {
            String token = TestTokens.sign(new JWSHeader.Builder(JWSAlgorithm.HS256).type(TestTokens.TYPE)
                    .keyID("gateway").build(), claims, new MACSigner(secret));
            assertRejected(token, Reason.ALGORITHM);
        }
    }

    @Test
    void rejectsRs256() throws JOSEException {
        RSAKey rsa = new RSAKeyGenerator(2048).keyID("gateway").generate();
        String token = TestTokens.sign(new JWSHeader.Builder(JWSAlgorithm.RS256).type(TestTokens.TYPE)
                .keyID("gateway").build(), claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build(),
                new RSASSASigner(rsa));
        assertRejected(token, Reason.ALGORITHM);
    }

    @Test
    void rejectsOtherEcAlgorithmsAndCurves() throws JOSEException {
        ECKey p384 = new ECKeyGenerator(Curve.P_384).keyID("gateway").generate();
        String token = TestTokens.sign(new JWSHeader.Builder(JWSAlgorithm.ES384).type(TestTokens.TYPE)
                .keyID("gateway").build(), claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build(),
                TestTokens.signer(p384));
        assertRejected(token, Reason.ALGORITHM);
    }

    @Test
    void rejectsAMissingOrForeignTyp() {
        JWTClaimsSet claims = claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build();
        assertRejected(TestTokens.sign(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("gateway").build(), claims,
                TestTokens.signer(GATEWAY)), Reason.TYPE);
        assertRejected(TestTokens.sign(header("gateway").type(JOSEObjectType.JWT).build(), claims,
                TestTokens.signer(GATEWAY)), Reason.TYPE);
    }

    @Test
    void rejectsAKidThatIsNotTheIssuer() {
        // signed with the gateway key under the gateway kid, but claims to be booking
        assertRejected(es256(GATEWAY, "gateway", bookingClaims().build()), Reason.ISSUER_MISMATCH);
        // no kid at all
        assertRejected(TestTokens.sign(new JWSHeader.Builder(JWSAlgorithm.ES256).type(TestTokens.TYPE).build(),
                claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build(), TestTokens.signer(GATEWAY)),
                Reason.ISSUER_NOT_ALLOWED);
    }

    @Test
    void rejectsABookingKeyTokenClaimingToBeTheGateway() {
        JWTClaimsSet claims = claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60))
                .claim("roles", List.of("USER", "ADMIN")).build();
        // honest kid: kid != iss
        assertRejected(es256(BOOKING, "svc:booking", claims), Reason.ISSUER_MISMATCH);
        // lying kid: the gateway's public key does not verify booking's signature
        assertRejected(es256(BOOKING, "gateway", claims), Reason.SIGNATURE);
    }

    @Test
    void rejectsATamperedPayload() {
        String token = gatewaySigner.sign(AUDIENCE, "u-1", List.of("USER"), null);
        String[] parts = token.split("\\.");
        String forged = b64(claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60))
                .claim("roles", List.of("USER", "ADMIN")).build().toString());
        assertRejected(parts[0] + "." + forged + "." + parts[2], Reason.SIGNATURE);
        // one flipped signature character
        char c = parts[2].charAt(5);
        assertRejected(parts[0] + "." + parts[1] + "." + parts[2].substring(0, 5) + (c == 'A' ? 'B' : 'A')
                + parts[2].substring(6), Reason.SIGNATURE);
    }

    @Test
    void rejectsAnUnknownIssuerEvenWithAKnownKey() {
        assertRejected(es256(PAYMENT, "svc:payment", claims("svc:payment", AUDIENCE, NOW, NOW.plusSeconds(60))
                .subject("svc:payment").claim("roles", List.of()).build()), Reason.ISSUER_NOT_ALLOWED);
    }

    // ---- rejected: a service can never speak for a user -----------------------------------------------------------

    @Test
    void rejectsAServiceTokenForAUser() {
        // the reviewer's probe: the committed svc:booking key, sub u-1, roles [USER, ADMIN]
        assertRejected(es256(BOOKING, "svc:booking", bookingClaims().subject("u-1")
                .claim("roles", List.of("USER", "ADMIN")).build()), Reason.SERVICE_IMPERSONATION);
        assertRejected(es256(BOOKING, "svc:booking", bookingClaims().subject("u-1").build()),
                Reason.SERVICE_IMPERSONATION);
        assertRejected(es256(BOOKING, "svc:booking", bookingClaims().subject("svc:notification").build()),
                Reason.SERVICE_IMPERSONATION);
        assertRejected(es256(BOOKING, "svc:booking", bookingClaims().subject(null).build()),
                Reason.SERVICE_IMPERSONATION);
    }

    @Test
    void rejectsAServiceTokenWithRoles() {
        assertRejected(es256(BOOKING, "svc:booking", bookingClaims().claim("roles", List.of("ADMIN")).build()),
                Reason.SERVICE_IMPERSONATION);
        assertRejected(es256(BOOKING, "svc:booking", bookingClaims().claim("roles", List.of("USER")).build()),
                Reason.SERVICE_IMPERSONATION);
    }

    @Test
    void theDefaultAllowListIsTheGatewayOnly() {
        var defaults = new InternalTokenVerifier(JWKS, new InternalIdentityProperties().getAllowedIssuers(), AUDIENCE,
                CLOCK);
        assertRejected(defaults, bookingSigner.signForService(AUDIENCE, null), Reason.ISSUER_NOT_ALLOWED);
        assertThat(defaults.verify(gatewaySigner.sign(AUDIENCE, "u-1", List.of("USER"), null)).subject())
                .isEqualTo("u-1");
    }

    @Test
    void rejectsAGatewayTokenThatNamesAService() {
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60))
                .subject("svc:booking").build()), Reason.SUBJECT);
    }

    // ---- rejected: headers ----------------------------------------------------------------------------------------

    @Test
    void rejectsTokensThatCarryTheirOwnKeyOrCriticalExtensions() throws Exception {
        JWTClaimsSet claims = claims("gateway", AUDIENCE, NOW, NOW.plusSeconds(60)).build();
        List<JWSHeader> headers = List.of(
                header("gateway").jwk(GATEWAY.toPublicJWK()).build(),
                header("gateway").jwkURL(URI.create("https://attacker.test/jwks.json")).build(),
                header("gateway").x509CertURL(URI.create("https://attacker.test/cert.pem")).build(),
                header("gateway").x509CertChain(List.of(Base64.encode("not-a-certificate"))).build(),
                header("gateway").customParam("x-surge-ext", "1").criticalParams(Set.of("x-surge-ext")).build());
        for (JWSHeader header : headers) {
            assertRejected(TestTokens.sign(header, claims, TestTokens.signer(GATEWAY)), Reason.UNSUPPORTED_HEADER);
        }
    }

    // ---- rejected: malformed --------------------------------------------------------------------------------------

    @Test
    void rejectsMissingOrMalformedClaims() {
        Instant exp = NOW.plusSeconds(60);
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).expirationTime(null).build()),
                Reason.MISSING_CLAIM);
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).issueTime(null).build()),
                Reason.MISSING_CLAIM);
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).jwtID(null).build()),
                Reason.MISSING_CLAIM);
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).audience((String) null)
                .build()), Reason.AUDIENCE);
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).claim("roles", "ADMIN")
                .build()), Reason.ROLES);
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp)
                .claim("roles", List.of("admin")).build()), Reason.ROLES);
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).subject("u-1\r\nX: y")
                .build()), Reason.SUBJECT);
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp).claim("rid", 7).build()),
                Reason.REQUEST_ID);
        assertRejected(es256(GATEWAY, "gateway", claims("gateway", AUDIENCE, NOW, exp)
                .notBeforeTime(Date.from(NOW.plusSeconds(30))).build()), Reason.NOT_YET_VALID);
    }

    @Test
    void rejectsGarbage() {
        for (String bad : List.of("", "garbage", "a.b.c", "a.b", "x".repeat(10_000))) {
            assertRejected(bad, Reason.MALFORMED);
        }
        assertRejected(null, Reason.MALFORMED);
    }

    @Test
    void errorMessagesNeverEchoTheToken() {
        String token = gatewaySigner.sign("catalog-service", "u-1", List.of("USER"), null);
        assertThatThrownBy(() -> verifier.verify(token)).hasMessage("Invalid internal identity token: AUDIENCE");
    }

    // ---- construction -------------------------------------------------------------------------------------------

    @Test
    void keySetMustHoldPublicP256KeysOnly() throws JOSEException {
        assertThatThrownBy(() -> new InternalTokenVerifier(new JWKSet(GATEWAY), Set.of("gateway"), AUDIENCE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("public");
        ECKey p384 = new ECKeyGenerator(Curve.P_384).keyID("gateway").generate();
        assertThatThrownBy(() -> new InternalTokenVerifier(new JWKSet(p384.toPublicJWK()), Set.of("gateway"),
                AUDIENCE)).isInstanceOf(IllegalArgumentException.class);
        RSAKey rsa = new RSAKeyGenerator(2048).keyID("gateway").generate();
        assertThatThrownBy(() -> new InternalTokenVerifier(new JWKSet(rsa.toPublicJWK()), Set.of("gateway"),
                AUDIENCE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalTokenVerifier(new JWKSet(List.of()), Set.of("gateway"), AUDIENCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalTokenVerifier(JWKS, Set.of("gateway"), " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
