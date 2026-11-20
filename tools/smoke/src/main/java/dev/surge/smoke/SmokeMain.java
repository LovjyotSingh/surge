package dev.surge.smoke;

import java.io.PrintStream;
import java.util.List;

/**
 * End-to-end smoke test of the running platform, through nginx like a browser. Exit codes: 0 every check passed,
 * 1 at least one check failed, 2 bad arguments.
 */
public final class SmokeMain {

    private SmokeMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System::getenv, System.out, System.err));
    }

    static int run(String[] args, java.util.function.UnaryOperator<String> env, PrintStream out, PrintStream err) {
        SmokeConfig cfg;
        try {
            cfg = SmokeConfig.parse(args, env);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(SmokeConfig.USAGE);
            return 2;
        }
        return run(cfg, Checks.all(), out);
    }

    static int run(SmokeConfig cfg, List<Check> checks, PrintStream out) {
        out.println("Surge smoke test against " + cfg.baseUrl() + " (mail API " + cfg.mailUrl() + ", event "
            + cfg.eventId() + ")");
        try (SmokeContext ctx = SmokeContext.of(cfg)) {
            return CheckRunner.exitCode(new CheckRunner(out).runAll(checks, ctx));
        }
    }
}
