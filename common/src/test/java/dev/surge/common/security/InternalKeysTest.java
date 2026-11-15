package dev.surge.common.security;

import static dev.surge.common.security.TestTokens.ecKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

/** Key loading, the committed development keys, and the production refusals. */
class InternalKeysTest {

    private static final ResourceLoader LOADER = new DefaultResourceLoader();

    @TempDir
    Path dir;

    @Test
    void theCommittedDevKeysAreOneP256KeyPerIssuerWithTheirPublicHalvesInTheJwks() throws Exception {
        JWKSet jwks = InternalKeys.loadVerificationKeys(null, SurgeEnv.DEV, LOADER);
        assertThat(jwks.getKeys()).extracting(JWK::getKeyID)
                .containsExactlyInAnyOrder("gateway", "svc:booking", "svc:notification");
        for (String issuer : InternalTokens.KNOWN_ISSUERS) {
            ECKey key = InternalKeys.loadSigningKey(null, issuer, SurgeEnv.DEV, LOADER);
            assertThat(key.isPrivate()).isTrue();
            assertThat(key.getKeyID()).isEqualTo(issuer);
            assertThat(jwks.getKeyByKeyId(issuer).computeThumbprint()).isEqualTo(key.computeThumbprint());
            // and they work end to end
            String token = new InternalTokenSigner(key, issuer, Clock.systemUTC()).sign("booking-service", null,
                    List.of(), null);
            assertThat(new InternalTokenVerifier(jwks, InternalTokens.KNOWN_ISSUERS, "booking-service",
                    Clock.systemUTC()).verify(token).issuer()).isEqualTo(issuer);
        }
        assertThat(InternalKeys.devThumbprints()).hasSize(3);
    }

    @Test
    void devKeyFileNamesDropTheSvcPrefix() {
        assertThat(InternalKeys.devKeyFile("gateway")).isEqualTo("gateway.jwk");
        assertThat(InternalKeys.devKeyFile("svc:booking")).isEqualTo("booking.jwk");
        assertThat(InternalKeys.devKeyFile("svc:notification")).isEqualTo("notification.jwk");
    }

    @Test
    void prodRefusesUnsetLocations() {
        assertThatThrownBy(() -> InternalKeys.loadVerificationKeys(null, SurgeEnv.PROD, LOADER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("jwks-location");
        assertThatThrownBy(() -> InternalKeys.loadVerificationKeys("  ", SurgeEnv.PROD, LOADER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> InternalKeys.loadSigningKey(null, "gateway", SurgeEnv.PROD, LOADER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("signing-key-location");
    }

    @Test
    void prodRefusesTheDevKeysEvenWhenConfiguredExplicitly() throws Exception {
        assertThatThrownBy(() -> InternalKeys.loadVerificationKeys("classpath:surge-dev-keys/internal-jwks.json",
                SurgeEnv.PROD, LOADER)).isInstanceOf(IllegalStateException.class).hasMessageContaining("development");
        assertThatThrownBy(() -> InternalKeys.loadSigningKey("classpath:surge-dev-keys/gateway.jwk", "gateway",
                SurgeEnv.PROD, LOADER)).isInstanceOf(IllegalStateException.class).hasMessageContaining("development");

        // a copy on disk, mixed in with a real key, is still caught by its thumbprint
        ECKey real = ecKey("gateway");
        JWK devBooking = InternalKeys.loadVerificationKeys(null, SurgeEnv.DEV, LOADER).getKeyByKeyId("svc:booking");
        Path jwks = Files.writeString(dir.resolve("jwks.json"),
                new JWKSet(List.of(real.toPublicJWK(), devBooking)).toString());
        assertThatThrownBy(() -> InternalKeys.loadVerificationKeys(jwks.toString(), SurgeEnv.PROD, LOADER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("development");
    }

    @Test
    void prodAcceptsRealKeysFromFiles() throws Exception {
        ECKey gateway = ecKey("gateway");
        Path key = Files.writeString(dir.resolve("gateway.jwk"), gateway.toJSONString());
        Path jwks = Files.writeString(dir.resolve("internal-jwks.json"), new JWKSet(gateway.toPublicJWK()).toString());

        assertThat(InternalKeys.loadSigningKey("file:" + key.toAbsolutePath(), "gateway", SurgeEnv.PROD, LOADER)
                .computeThumbprint()).isEqualTo(gateway.computeThumbprint());
        // a bare path is a file path
        assertThat(InternalKeys.loadVerificationKeys(jwks.toAbsolutePath().toString(), SurgeEnv.PROD, LOADER)
                .getKeys()).hasSize(1);
    }

    @Test
    void rejectsKeysThatDoNotFit() throws Exception {
        ECKey gateway = ecKey("gateway");
        Path privateInJwks = Files.writeString(dir.resolve("jwks.json"), new JWKSet(gateway).toString(false));
        assertThatThrownBy(() -> InternalKeys.loadVerificationKeys(privateInJwks.toString(), SurgeEnv.DEV, LOADER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("public");

        Path publicOnly = Files.writeString(dir.resolve("public.jwk"), gateway.toPublicJWK().toJSONString());
        assertThatThrownBy(() -> InternalKeys.loadSigningKey(publicOnly.toString(), "gateway", SurgeEnv.DEV, LOADER))
                .isInstanceOf(IllegalStateException.class);

        Path wrongIssuer = Files.writeString(dir.resolve("booking.jwk"), ecKey("svc:booking").toJSONString());
        assertThatThrownBy(() -> InternalKeys.loadSigningKey(wrongIssuer.toString(), "gateway", SurgeEnv.DEV, LOADER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("kid");

        Path garbage = Files.writeString(dir.resolve("garbage.jwk"), "{\"kty\":\"EC\",\"d\":\"c2VjcmV0");
        assertThatThrownBy(() -> InternalKeys.loadSigningKey(garbage.toString(), "gateway", SurgeEnv.DEV, LOADER))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    // nothing in the whole cause chain may echo key material
                    var out = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(out));
                    assertThat(out.toString()).doesNotContain("c2VjcmV0");
                });

        assertThatThrownBy(() -> InternalKeys.loadSigningKey(dir.resolve("missing.jwk").toString(), "gateway",
                SurgeEnv.DEV, LOADER)).isInstanceOf(IllegalStateException.class).hasMessageContaining("does not exist");
    }

    @Test
    void onlyFileAndClasspathLocationsAreAllowed() {
        assertThatThrownBy(() -> InternalKeys.loadVerificationKeys("https://example.test/jwks.json", SurgeEnv.DEV,
                LOADER)).isInstanceOf(IllegalStateException.class).hasMessageContaining("file:");
    }

    @Test
    void surgeEnvIsExactlyDevOrProd() {
        assertThat(SurgeEnv.parse("dev")).isEqualTo(SurgeEnv.DEV);
        assertThat(SurgeEnv.parse("prod")).isEqualTo(SurgeEnv.PROD);
        for (String bad : List.of("Prod", "PROD", "production", "", " dev")) {
            assertThatThrownBy(() -> SurgeEnv.parse(bad)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("SURGE_ENV must be exactly 'dev' or 'prod'");
        }
        var env = new org.springframework.mock.env.MockEnvironment();
        assertThat(SurgeEnv.from(env)).isEqualTo(SurgeEnv.DEV);
        env.setProperty("surge.env", "prod");
        assertThat(SurgeEnv.from(env)).isEqualTo(SurgeEnv.PROD);
    }
}
