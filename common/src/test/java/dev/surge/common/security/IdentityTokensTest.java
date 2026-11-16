package dev.surge.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;

/** The test-jar helper the services use: its tokens verify exactly like the gateway's, and nothing more. */
class IdentityTokensTest {

    private static final JWKSet DEV_JWKS = InternalKeys.loadVerificationKeys(null, SurgeEnv.DEV,
            new DefaultResourceLoader());

    private static InternalIdentity verify(String token, String audience, String... issuers) {
        return new InternalTokenVerifier(DEV_JWKS, Set.of(issuers), audience).verify(token);
    }

    @Test
    void gatewayTokensCarryTheUserAndRoles() {
        InternalIdentity admin = verify(IdentityTokens.admin("booking-service", "u-9"), "booking-service", "gateway");
        assertThat(admin.isGatewayAdmin()).isTrue();
        assertThat(admin.roles()).containsExactly("USER", "ADMIN");

        InternalIdentity user = verify(IdentityTokens.ordinaryUser("booking-service", "u-1"), "booking-service",
                "gateway");
        assertThat(user.subject()).isEqualTo("u-1");
        assertThat(user.roles()).containsExactly("USER");
        assertThat(verify(IdentityTokens.user("booking-service", "u-1"), "booking-service", "gateway").roles())
                .isEmpty();
        assertThat(verify(IdentityTokens.anonymous("booking-service"), "booking-service", "gateway").isAnonymous())
                .isTrue();
    }

    @Test
    void serviceTokensSpeakOnlyForTheService() {
        InternalIdentity booking = verify(IdentityTokens.service("svc:booking", "payment-service"),
                "payment-service", "svc:booking");
        assertThat(booking.issuer()).isEqualTo("svc:booking");
        assertThat(booking.subject()).isEqualTo("svc:booking");
        assertThat(booking.roles()).isEmpty();
    }

    @Test
    void tokensAreBoundToTheirAudience() {
        String token = IdentityTokens.ordinaryUser("catalog-service", "u-1");
        assertThatThrownBy(() -> verify(token, "booking-service", "gateway"))
                .isInstanceOf(InvalidInternalTokenException.class);
    }

    @Test
    void runAsSetsTheChainsAuthenticationAndClearsIt() {
        IdentityTokens.runAs(IdentityTokens.admin("booking-service", "u-9"), "booking-service",
                () -> assertThat(CurrentIdentity.requireAdmin().subject()).isEqualTo("u-9"));
        assertThat(CurrentIdentity.get()).isEmpty();
        IdentityTokens.runAs(null, "booking-service", () -> assertThat(CurrentIdentity.get()).isEmpty());
        IdentityTokens.runAs(IdentityTokens.service("svc:notification", "booking-service"), "booking-service",
                () -> assertThat(CurrentIdentity.get()).hasValueSatisfying(i -> assertThat(i.isUser()).isFalse()));
    }

    /** Test users are verified unless a test asks for an unverified one (holds need a verified email, S5). */
    @Test
    void testUsersAreVerifiedUnlessAskedOtherwise() {
        assertThat(verify(IdentityTokens.ordinaryUser("booking-service", "u-1"), "booking-service", "gateway")
                .isEmailVerified()).isTrue();
        assertThat(verify(IdentityTokens.admin("booking-service", "u-9"), "booking-service", "gateway")
                .isEmailVerified()).isTrue();
        InternalIdentity unverified = verify(IdentityTokens.unverifiedUser("booking-service", "u-2"),
                "booking-service", "gateway");
        assertThat(unverified.isEmailVerified()).isFalse();
        assertThat(unverified.roles()).containsExactly("USER");
        assertThat(verify(IdentityTokens.anonymous("booking-service"), "booking-service", "gateway").emailVerified())
                .isNull();
    }

    @Test
    void requireVerifiedUserAcceptsOnlyAGatewayUserWithAVerifiedEmail() {
        IdentityTokens.runAs(IdentityTokens.ordinaryUser("booking-service", "u-1"), "booking-service",
                () -> assertThat(CurrentIdentity.requireVerifiedUser()).isEqualTo("u-1"));
        IdentityTokens.runAs(IdentityTokens.unverifiedUser("booking-service", "u-2"), "booking-service",
                () -> assertThatThrownBy(CurrentIdentity::requireVerifiedUser)
                        .isInstanceOfSatisfying(InternalIdentityException.class, e -> {
                            assertThat(e.getStatusCode().value()).isEqualTo(403);
                            assertThat(e.getBody().getType().toString())
                                    .isEqualTo("https://surge.dev/problems/email-unverified");
                        }));
        for (String token : new String[] {IdentityTokens.anonymous("booking-service"),
                IdentityTokens.service("svc:notification", "booking-service"), null}) {
            IdentityTokens.runAs(token, "booking-service", () -> assertThatThrownBy(CurrentIdentity::requireVerifiedUser)
                    .isInstanceOfSatisfying(InternalIdentityException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(401)));
        }
    }

    @Test
    void thePostProcessorSignsAFreshTokenPerRequest() {
        var processor = IdentityTokens.asOrdinaryUser("booking-service", "u-1");
        MockHttpServletRequest first = processor.postProcessRequest(new MockHttpServletRequest());
        MockHttpServletRequest second = processor.postProcessRequest(new MockHttpServletRequest());
        assertThat(first.getHeader(IdentityTokens.HEADER)).isNotEqualTo(second.getHeader(IdentityTokens.HEADER));
        assertThat(verify(first.getHeader(IdentityTokens.HEADER), "booking-service", "gateway").subject())
                .isEqualTo("u-1");
    }
}
