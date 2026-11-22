package dev.surge.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SmokeConfigTest {

    @Test
    void defaultsPointAtTheLocalStack() {
        SmokeConfig c = SmokeConfig.parse(new String[0], Map.of("SURGE_ADMIN_PASSWORD", "pw")::get);

        assertThat(c.baseUrl().toString()).isEqualTo("http://127.0.0.1:8080");
        assertThat(c.mailUrl().toString()).isEqualTo("http://127.0.0.1:8025");
        assertThat(c.adminEmail()).isEqualTo("admin@surge.local");
        assertThat(c.eventId()).isEqualTo(1);
    }

    @Test
    void environmentThenFlagsOverrideTheDefaults() {
        Map<String, String> env = Map.of("SURGE_BASE_URL", "http://web:8080", "SURGE_MAIL_URL", "http://mailpit:8025",
            "SURGE_ADMIN_EMAIL", "boss@x", "SURGE_ADMIN_PASSWORD", "secret-from-env");

        SmokeConfig fromEnv = SmokeConfig.parse(new String[0], env::get);
        assertThat(fromEnv.baseUrl().toString()).isEqualTo("http://web:8080");
        assertThat(fromEnv.mailUrl().toString()).isEqualTo("http://mailpit:8025");
        assertThat(fromEnv.adminEmail()).isEqualTo("boss@x");
        assertThat(fromEnv.adminPassword()).isEqualTo("secret-from-env");

        SmokeConfig flags = SmokeConfig.parse(new String[] {"--base-url", "http://h:1/", "--event", "7",
            "--admin-password", "flag-pw"}, env::get);
        assertThat(flags.baseUrl().toString()).isEqualTo("http://h:1");
        assertThat(flags.eventId()).isEqualTo(7);
        assertThat(flags.adminPassword()).isEqualTo("flag-pw");
    }

    @Test
    void aMissingPasswordIsRefused() {
        assertThatThrownBy(() -> SmokeConfig.parse(new String[0], k -> null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SURGE_ADMIN_PASSWORD");
    }

    @Test
    void unknownOptionsAndBadNumbersAreRefused() {
        assertThatThrownBy(() -> SmokeConfig.parse(new String[] {"--nope"}, k -> "x"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--nope");
        assertThatThrownBy(() -> SmokeConfig.parse(new String[] {"--event", "abc"}, k -> "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SmokeConfig.parse(new String[] {"--event"}, k -> "x"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--event");
    }

    /** S10: compose mounts the admin password as a secret file and names it in SURGE_ADMIN_PASSWORD_FILE. */
    @Test
    void readsThePasswordFromTheFileNamedInTheEnvironment(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        java.nio.file.Path file = java.nio.file.Files.writeString(dir.resolve("SURGE_ADMIN_PASSWORD"),
            "password-from-a-file\r\n");

        SmokeConfig fromFile = SmokeConfig.parse(new String[0], Map.of("SURGE_ADMIN_PASSWORD_FILE", file.toString())::get);
        assertThat(fromFile.adminPassword()).isEqualTo("password-from-a-file");

        // the flag still wins
        assertThat(SmokeConfig.parse(new String[] {"--admin-password", "flag-pw"},
            Map.of("SURGE_ADMIN_PASSWORD_FILE", file.toString())::get).adminPassword()).isEqualTo("flag-pw");

        // both ways at once is refused, an unreadable file too, and neither shows a value
        assertThatThrownBy(() -> SmokeConfig.parse(new String[0], Map.of(
                "SURGE_ADMIN_PASSWORD", "env-pw", "SURGE_ADMIN_PASSWORD_FILE", file.toString())::get))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SURGE_ADMIN_PASSWORD_FILE")
            .hasMessageNotContaining("env-pw").hasMessageNotContaining("password-from-a-file");
        assertThatThrownBy(() -> SmokeConfig.parse(new String[0],
                Map.of("SURGE_ADMIN_PASSWORD_FILE", dir.resolve("missing").toString())::get))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SURGE_ADMIN_PASSWORD_FILE");
    }

    @Test
    void thePasswordNeverShowsInToString() {
        SmokeConfig c = SmokeConfig.parse(new String[0], Map.of("SURGE_ADMIN_PASSWORD", "hunter2hunter2")::get);
        assertThat(c.toString()).doesNotContain("hunter2");
    }

    private static SmokeConfig parseWith(String baseUrl, String... extra) {
        String[] args = new String[extra.length + 2];
        args[0] = "--base-url";
        args[1] = baseUrl;
        System.arraycopy(extra, 0, args, 2, extra.length);
        return SmokeConfig.parse(args, Map.of("SURGE_ADMIN_PASSWORD", "pw")::get);
    }

    @Test
    void localBaseUrlsNeedNoFlag() {
        for (String url : new String[] {"http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080",
            "http://web:8080", "http://web", "http://nginx.localhost", "https://shop.test", "http://LOCALHOST"}) {
            assertThat(parseWith(url).baseUrl().toString()).isEqualTo(url);
        }
    }

    @Test
    void aNonLocalBaseUrlIsRefusedWithoutDestructive() {
        for (String url : new String[] {"https://surge.example.com", "http://203.0.113.9:8080", "https://example.org",
            "http://evil.localhost.example.com", "http://web.prod.internal"}) {
            assertThatThrownBy(() -> parseWith(url)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--destructive").hasMessageContaining("Refusing");
        }
    }

    @Test
    void destructiveAllowsANonLocalBaseUrl() {
        assertThat(parseWith("https://surge.example.com", "--destructive").baseUrl().getHost())
            .isEqualTo("surge.example.com");
    }

    @Test
    void theRefusalExitsWithCode2AndSaysWhy() {
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = SmokeMain.run(new String[] {"--base-url", "https://surge.example.com"}, Map.of("SURGE_ADMIN_PASSWORD", "pw")::get,
            new java.io.PrintStream(java.io.OutputStream.nullOutputStream()), new java.io.PrintStream(err));
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("destructive").contains("surge.example.com");
    }
}
