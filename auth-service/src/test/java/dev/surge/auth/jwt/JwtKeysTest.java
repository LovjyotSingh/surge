package dev.surge.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.Curve;
import dev.surge.auth.api.UserView;
import dev.surge.common.security.SurgeEnv;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Loading the user-JWT signing key from SURGE_JWT_KEY_FILE (PEM or JWK), without a Boot context. */
class JwtKeysTest {

    private static final DefaultResourceLoader LOADER = new DefaultResourceLoader();

    @TempDir
    Path dir;

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private Path write(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static UserView user() {
        return new UserView("u-7", "ada@example.com", "Ada", List.of("USER"), true);
    }

    @Test
    void aPkcs8PemKeyLoadsWithItsThumbprintAsAStableKid() throws Exception {
        KeyPair pair = rsa(2048);
        Path file = write("jwt.pem", pem("PRIVATE KEY", pair.getPrivate().getEncoded()));

        RSAKey first = JwtKeys.load(file.toString(), null, SurgeEnv.PROD, LOADER).current();
        RSAKey second = JwtKeys.load(file.toString(), null, SurgeEnv.PROD, LOADER).current();

        assertThat(first.isPrivate()).isTrue();
        assertThat(first.getKeyID()).isEqualTo(first.computeThumbprint().toString()).isEqualTo(second.getKeyID());
        assertThat(first.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(first.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(first.toRSAPublicKey()).isEqualTo(pair.getPublic());
    }

    @Test
    void aPrivateJwkKeepsItsKid() throws Exception {
        RSAKey jwk = new RSAKeyGenerator(2048).keyID("surge-2026-11").generate();
        Path file = write("jwt.jwk", jwk.toJSONString());
        RSAKey loaded = JwtKeys.load("file:" + file, null, SurgeEnv.PROD, LOADER).current();
        assertThat(loaded.getKeyID()).isEqualTo("surge-2026-11");
        assertThat(loaded.toRSAPublicKey()).isEqualTo(jwk.toRSAPublicKey());
    }

    /** "Tokens survive an auth restart": two instances loading the same file sign and verify interchangeably. */
    @Test
    void aTokenFromOneInstanceVerifiesAgainstTheJwksOfAnotherLoadingTheSameKey() throws Exception {
        Path file = write("jwt.pem", pem("PRIVATE KEY", rsa(2048).getPrivate().getEncoded()));
        JwtKeys before = JwtKeys.load(file.toString(), null, SurgeEnv.PROD, LOADER);
        JwtKeys afterRestart = JwtKeys.load(file.toString(), null, SurgeEnv.PROD, LOADER);

        String token = new TokenService(before.current(), Clock.systemUTC()).issue(user()).token();

        JWKSet served = JWKSet.parse(afterRestart.publicJwkSet().toString());
        RSAKey key = (RSAKey) served.getKeys().get(0);
        Jwt jwt = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(token);
        assertThat(jwt.getSubject()).isEqualTo("u-7");
        assertThat(jwt.getHeaders().get("kid")).isEqualTo(key.getKeyID());
    }

    @Test
    void thePreviousKeyIsPublishedNextToTheCurrentOneForRotation() throws Exception {
        KeyPair old = rsa(2048);
        Path current = write("new.pem", pem("PRIVATE KEY", rsa(2048).getPrivate().getEncoded()));
        // the previous key may be given as its public half only
        Path previous = write("old.pem", pem("PUBLIC KEY", old.getPublic().getEncoded()));

        JwtKeys keys = JwtKeys.load(current.toString(), previous.toString(), SurgeEnv.PROD, LOADER);
        JWKSet jwks = keys.publicJwkSet();

        assertThat(jwks.getKeys()).hasSize(2);
        assertThat(jwks.getKeys().get(0).getKeyID()).isEqualTo(keys.current().getKeyID());
        RSAKey published = (RSAKey) jwks.getKeys().get(1);
        assertThat(published.toRSAPublicKey()).isEqualTo(old.getPublic());
        assertThat(published.getKeyID()).isNotEqualTo(keys.current().getKeyID());
        assertThat(jwks.getKeys()).allSatisfy(k -> assertThat(k.isPrivate()).isFalse());
        assertThat(jwks.toString()).doesNotContain("\"d\"");
    }

    @Test
    void thePreviousKeyCannotBeTheCurrentKey() throws Exception {
        Path current = write("jwt.pem", pem("PRIVATE KEY", rsa(2048).getPrivate().getEncoded()));
        assertThatThrownBy(() -> JwtKeys.load(current.toString(), current.toString(), SurgeEnv.PROD, LOADER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SURGE_JWT_PREVIOUS_KEY_FILE");
    }

    @Test
    void unusableKeysAreRefusedWithoutEchoingThem() throws Exception {
        KeyPair small = rsa(1024);
        Path tooSmall = write("small.pem", pem("PRIVATE KEY", small.getPrivate().getEncoded()));
        assertThatThrownBy(() -> JwtKeys.load(tooSmall.toString(), null, SurgeEnv.DEV, LOADER))
                .hasMessageContaining("at least 2048 bits");

        Path publicOnly = write("public.pem", pem("PUBLIC KEY", rsa(2048).getPublic().getEncoded()));
        assertThatThrownBy(() -> JwtKeys.load(publicOnly.toString(), null, SurgeEnv.DEV, LOADER))
                .hasMessageContaining("private");

        Path ec = write("ec.jwk", new ECKeyGenerator(Curve.P_256).keyID("x").generate().toJSONString());
        assertThatThrownBy(() -> JwtKeys.load(ec.toString(), null, SurgeEnv.DEV, LOADER))
                .hasMessageContaining("RSA");

        String garbage = "-----BEGIN PRIVATE KEY-----\nTUlJc2VjcmV0LW1hdGVyaWFs\n-----END PRIVATE KEY-----\n";
        Path broken = write("broken.pem", garbage);
        assertThatThrownBy(() -> JwtKeys.load(broken.toString(), null, SurgeEnv.DEV, LOADER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("TUlJc2VjcmV0LW1hdGVyaWFs");

        assertThatThrownBy(() -> JwtKeys.load(dir.resolve("missing.pem").toString(), null, SurgeEnv.DEV, LOADER))
                .hasMessageContaining("does not exist");
    }

    @Test
    void prodNeedsAKeyFileWhileDevGeneratesOne() {
        assertThatThrownBy(() -> JwtKeys.load("", null, SurgeEnv.PROD, LOADER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SURGE_JWT_KEY_FILE");
        assertThatThrownBy(() -> JwtKeys.load(null, null, SurgeEnv.PROD, LOADER))
                .hasMessageContaining("SURGE_JWT_KEY_FILE");

        JwtKeys generated = JwtKeys.load(" ", null, SurgeEnv.DEV, LOADER);
        assertThat(generated.current().isPrivate()).isTrue();
        assertThat(generated.current().size()).isGreaterThanOrEqualTo(2048);
        assertThat(generated.publicJwkSet().getKeys()).hasSize(1);
        // a new process would generate another one: that is exactly why prod refuses this
        assertThat(JwtKeys.load(null, null, SurgeEnv.DEV, LOADER).current().getKeyID())
                .isNotEqualTo(generated.current().getKeyID());
    }
}
