package dev.surge.common.security.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JwtKeyGenTest {

    @TempDir
    Path dir;

    @Test
    void writesAPrivateRs256JwkNamedByItsThumbprint() throws Exception {
        Path file = dir.resolve("keys/jwt.jwk");
        RSAKey returned = JwtKeyGen.generate(file, false);

        JWK parsed = JWK.parse(Files.readString(file));
        assertThat(parsed).isInstanceOf(RSAKey.class);
        RSAKey key = (RSAKey) parsed;
        assertThat(key.isPrivate()).isTrue();
        assertThat(key.size()).isEqualTo(2048);
        assertThat(key.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(key.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString()).isEqualTo(returned.getKeyID());
        try (var left = Files.list(file.getParent())) {
            assertThat(left.toList()).containsExactly(file);
        }
    }

    @Test
    void neverOverwritesWithoutForce() throws Exception {
        Path file = dir.resolve("jwt.jwk");
        String first = JwtKeyGen.generate(file, false).getKeyID();
        assertThatThrownBy(() -> JwtKeyGen.generate(file, false)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(JWK.parse(Files.readString(file)).getKeyID()).isEqualTo(first);

        String second = JwtKeyGen.generate(file, true).getKeyID();
        assertThat(second).isNotEqualTo(first);
        assertThat(JWK.parse(Files.readString(file)).getKeyID()).isEqualTo(second);
    }

    @Test
    void usageErrors() {
        assertThat(JwtKeyGen.run(new String[0])).isEqualTo(2);
        assertThat(JwtKeyGen.run(new String[] {"--nope", "x"})).isEqualTo(2);
        assertThat(JwtKeyGen.run(new String[] {"a", "b"})).isEqualTo(2);
    }
}
