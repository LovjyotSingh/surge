package dev.surge.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LoadTestConfigTest {

    @Test
    void parsesDefaults() {
        LoadTestConfig c = LoadTestConfig.parse(new String[] {});
        assertThat(c.users()).isEqualTo(10000);
        assertThat(c.eventId()).isEqualTo(1);
        assertThat(c.baseUrl()).isEqualTo(URI.create("http://localhost:8080"));
        assertThat(c.outDir()).isEqualTo(Path.of("reports"));
        assertThat(c.reset()).isTrue();
        assertThat(c.hotShare()).isEqualTo(0.7);
        assertThat(c.maxAttempts()).isEqualTo(10);
    }

    @Test
    void parsesFlags() {
        LoadTestConfig c = LoadTestConfig.parse(new String[] {
            "--users", "50", "--no-reset", "--event", "3", "--base-url", "http://x:9",
            "--out", "o", "--hot-share", "0.5", "--max-attempts", "4"});
        assertThat(c.users()).isEqualTo(50);
        assertThat(c.reset()).isFalse();
        assertThat(c.eventId()).isEqualTo(3);
        assertThat(c.baseUrl()).isEqualTo(URI.create("http://x:9"));
        assertThat(c.outDir()).isEqualTo(Path.of("o"));
        assertThat(c.hotShare()).isEqualTo(0.5);
        assertThat(c.maxAttempts()).isEqualTo(4);
    }

    @Test
    void rejectsUnknownFlagAndMissingValue() {
        assertThatThrownBy(() -> LoadTestConfig.parse(new String[] {"--bogus"}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LoadTestConfig.parse(new String[] {"--users"}))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
