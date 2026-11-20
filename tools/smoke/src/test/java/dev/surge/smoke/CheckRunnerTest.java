package dev.surge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckRunnerTest {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final CheckRunner runner = new CheckRunner(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    private final SmokeContext ctx = SmokeContext.of(new SmokeConfig(URI.create("http://127.0.0.1:1"),
        URI.create("http://127.0.0.1:1"), "admin@x", "pw", 1));

    private String output() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    interface Body {
        String run() throws Exception;
    }

    private static Check check(String name, Duration timeout, Body body) {
        return new Check() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration timeout() {
                return timeout;
            }

            @Override
            public String run(SmokeContext c) throws Exception {
                return body.run();
            }
        };
    }

    private static Check check(String name, Body body) {
        return check(name, Duration.ofSeconds(5), body);
    }

    @Test
    void allPassingChecksGiveExitCodeZero() {
        List<CheckResult> results = runner.runAll(List.of(check("a", () -> "fine"), check("b", () -> "ok")), ctx);

        assertThat(results).extracting(CheckResult::passed).containsExactly(true, true);
        assertThat(CheckRunner.exitCode(results)).isZero();
        assertThat(output()).contains("PASS").contains("a").contains("fine").contains("2 passed, 0 failed");
    }

    @Test
    void oneFailureGivesExitCodeOneAndPrintsItsDetail() {
        List<CheckResult> results = runner.runAll(List.of(
            check("a", () -> "fine"),
            check("b", () -> {
                throw new CheckFailure("expected 201 but got 500");
            })), ctx);

        assertThat(results).extracting(CheckResult::passed).containsExactly(true, false);
        assertThat(CheckRunner.exitCode(results)).isEqualTo(1);
        assertThat(output()).contains("FAIL").contains("expected 201 but got 500").contains("1 passed, 1 failed");
    }

    @Test
    void anUnexpectedExceptionFailsOnlyThatCheckAndTheRestStillRun() {
        List<String> ran = new ArrayList<>();
        List<CheckResult> results = runner.runAll(List.of(
            check("boom", () -> {
                throw new IllegalStateException("kaput");
            }),
            check("after", () -> {
                ran.add("after");
                return "ran";
            })), ctx);

        assertThat(ran).containsExactly("after");
        assertThat(results).extracting(CheckResult::passed).containsExactly(false, true);
        assertThat(results.get(0).detail()).contains("IllegalStateException").contains("kaput");
        assertThat(output()).doesNotContain("\tat ");
    }

    @Test
    void aCheckThatOverrunsItsTimeoutFailsAndTheNextOneStillRuns() {
        long t0 = System.nanoTime();
        List<CheckResult> results = runner.runAll(List.of(
            check("slow", Duration.ofMillis(200), () -> {
                Thread.sleep(30_000);
                return "never";
            }),
            check("next", () -> "ok")), ctx);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(elapsedMs).isLessThan(5_000);
        assertThat(results).extracting(CheckResult::passed).containsExactly(false, true);
        assertThat(results.get(0).detail()).contains("timed out");
        assertThat(CheckRunner.exitCode(results)).isEqualTo(1);
    }

    @Test
    void anAssertionErrorDoesNotEscapeTheRunner() {
        List<CheckResult> results = runner.runAll(List.of(check("err", () -> {
            throw new AssertionError("bad");
        })), ctx);

        assertThat(results.get(0).passed()).isFalse();
    }

    @Test
    void noChecksIsNotASuccess() {
        assertThat(CheckRunner.exitCode(List.of())).isEqualTo(1);
    }
}
