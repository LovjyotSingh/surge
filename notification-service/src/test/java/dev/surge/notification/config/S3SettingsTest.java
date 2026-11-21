package dev.surge.notification.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * SEC-16 (S10): application.yml's S3 keys default to the local placeholder {@code surge-local}, which S3Mock accepts.
 * With SURGE_ENV=prod the ticket store refuses to start on them, or on no keys at all; a public deployment also refuses
 * a plain-HTTP endpoint (S3Mock, or any S3 the keys and the ticket PDFs would cross unencrypted).
 */
class S3SettingsTest {

    private static MockEnvironment env(String surgeEnv, boolean publicDeployment) {
        return new MockEnvironment().withProperty("surge.env", surgeEnv)
                .withProperty("surge.security.public-deployment", Boolean.toString(publicDeployment));
    }

    @Test
    void prodRefusesThePlaceholderOrMissingKeysWithoutShowingThem() {
        for (List<String> keys : List.of(List.of("surge-local", "a-real-secret-key-0123"),
                List.of("AKIAREALACCESSKEY", "surge-local"), List.of("", ""), List.of("AKIAREALACCESSKEY", " "))) {
            assertThatThrownBy(() -> new S3Config().s3Client("", "ap-south-1", keys.get(0), keys.get(1),
                    env("prod", false)))
                    .as(keys.toString())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("S3_ACCESS_KEY and S3_SECRET_KEY")
                    .hasMessageNotContaining("a-real-secret-key-0123").hasMessageNotContaining("AKIAREALACCESSKEY");
        }
    }

    @Test
    void devKeepsThePlaceholderForS3Mock() {
        assertThatCode(() -> new S3Config().s3Client("http://s3mock:9090", "ap-south-1", "surge-local",
                "surge-local", env("dev", false)).close()).doesNotThrowAnyException();
    }

    @Test
    void prodWithRealKeysStartsOnAnyEndpointWhenPrivate() {
        assertThatCode(() -> new S3Config().s3Client("http://s3mock:9090", "ap-south-1", "AKIAREALACCESSKEY",
                "a-real-secret-key-0123", env("prod", false)).close()).doesNotThrowAnyException();
    }

    @Test
    void aPublicDeploymentRefusesAPlainHttpEndpoint() {
        assertThatThrownBy(() -> new S3Config().s3Client("http://s3mock:9090", "ap-south-1", "AKIAREALACCESSKEY",
                "a-real-secret-key-0123", env("prod", true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("S3_ENDPOINT");
        for (String endpoint : List.of("", "https://minio.example.com:9000")) {
            assertThatCode(() -> new S3Config().s3Client(endpoint, "ap-south-1", "AKIAREALACCESSKEY",
                    "a-real-secret-key-0123", env("prod", true)).close()).as(endpoint).doesNotThrowAnyException();
        }
    }
}
