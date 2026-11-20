package dev.surge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.smoke.StubServer.Reply;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmokeMainTest {

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);

    @Test
    void badArgumentsExitWithTwoAndPrintUsageButNoPassword() {
        int code = SmokeMain.run(new String[] {"--bogus"}, k -> "secret-pw", out, err);

        assertThat(code).isEqualTo(2);
        assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("Unknown option").contains("Usage:")
            .doesNotContain("secret-pw");
    }

    @Test
    void aMissingPasswordExitsWithTwo() {
        int code = SmokeMain.run(new String[0], k -> null, out, err);

        assertThat(code).isEqualTo(2);
        assertThat(errBuf.toString(StandardCharsets.UTF_8)).contains("SURGE_ADMIN_PASSWORD");
    }

    @Test
    void anUnreachableStackFailsEveryCheckWithoutAStackTraceAndExitsOne() {
        // Port 1 on localhost refuses connections at once.
        SmokeConfig cfg = new SmokeConfig(URI.create("http://127.0.0.1:1"), URI.create("http://127.0.0.1:1"),
            "a@b", "pw", 1);

        int code = SmokeMain.run(cfg, Checks.all(), out);

        String text = outBuf.toString(StandardCharsets.UTF_8);
        assertThat(code).isEqualTo(1);
        assertThat(text).contains("0 passed, 10 failed").contains("cannot reach").doesNotContain("\tat ");
    }

    @Test
    void exitCodeIsZeroOnlyWhenEveryCheckPassesAgainstTheStub() throws Exception {
        try (StubServer stub = new StubServer().on("GET /ok", Reply.of(200, "{}"))
            .on("GET /bad", Reply.of(500, ""))) {
            SmokeConfig cfg = new SmokeConfig(stub.baseUrl(), stub.baseUrl(), "a@b", "pw", 1);
            Check ok = simple("ok", "/ok");
            Check bad = simple("bad", "/bad");

            assertThat(SmokeMain.run(cfg, List.of(ok, ok), out)).isZero();
            assertThat(SmokeMain.run(cfg, List.of(ok, bad, ok), out)).isEqualTo(1);
            // The check after the failing one still ran.
            assertThat(stub.received.stream().filter("GET /ok"::equals).count()).isEqualTo(4);
        }
    }

    private static Check simple(String name, String path) {
        return new Check() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration timeout() {
                return Duration.ofSeconds(5);
            }

            @Override
            public String run(SmokeContext ctx) {
                SmokeContext.expect(ctx.api().send("GET", path, null, Map.of(), null), 200, path);
                return "ok";
            }
        };
    }
}
