package dev.surge.common.security.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InternalKeyGenTest {

    @TempDir
    Path dir;

    @Test
    void writesOnePrivateKeyPerIssuerAndAPublicJwks() throws Exception {
        InternalKeyGen.generate(dir, false, false);

        JWKSet jwks = JWKSet.parse(Files.readString(dir.resolve("internal-jwks.json")));
        assertThat(jwks.getKeys()).allSatisfy(key -> assertThat(key.isPrivate()).isFalse());
        for (Map.Entry<String, String> file : Map.of("gateway", "gateway.jwk", "svc:booking", "booking.jwk",
                "svc:notification", "notification.jwk").entrySet()) {
            ECKey key = ECKey.parse(Files.readString(dir.resolve(file.getValue())));
            assertThat(key.isPrivate()).isTrue();
            assertThat(key.getKeyID()).isEqualTo(file.getKey());
            assertThat(key.getCurve().getName()).isEqualTo("P-256");
            JWK published = jwks.getKeyByKeyId(file.getKey());
            assertThat(published.computeThumbprint()).isEqualTo(key.computeThumbprint());
            if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null) {
                assertThat(Files.getPosixFilePermissions(dir.resolve(file.getValue())))
                        .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            }
        }
    }

    @Test
    void neverOverwritesWithoutForce() throws Exception {
        InternalKeyGen.generate(dir, false, false);
        String before = Files.readString(dir.resolve("gateway.jwk"));
        assertThatThrownBy(() -> InternalKeyGen.generate(dir, false, false))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(dir.resolve("gateway.jwk"))).isEqualTo(before);

        InternalKeyGen.generate(dir, true, false);
        assertThat(Files.readString(dir.resolve("gateway.jwk"))).isNotEqualTo(before);
    }

    @Test
    void devKeysCarryALabel() throws Exception {
        InternalKeyGen.generate(dir, false, true);
        assertThat(Files.readString(dir.resolve("gateway.jwk"))).contains("DEV ONLY");
        assertThat(Files.readString(dir.resolve("internal-jwks.json"))).contains("DEV ONLY");
        // still parseable
        ECKey.parse(Files.readString(dir.resolve("gateway.jwk")));
        JWKSet.parse(Files.readString(dir.resolve("internal-jwks.json")));
    }

    @Test
    void usage() {
        assertThat(InternalKeyGen.run(new String[0])).isEqualTo(2);
        assertThat(InternalKeyGen.run(new String[] {"--bogus", dir.toString()})).isEqualTo(2);
        assertThat(InternalKeyGen.run(new String[] {dir.toString()})).isZero();
        assertThat(InternalKeyGen.run(new String[] {dir.toString()})).isEqualTo(1);
    }
}
