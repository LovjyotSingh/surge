package dev.surge.smoke;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the checks one after the other. A check that fails, throws or overruns its timeout is reported and the next
 * one still runs (the cleanup at the end must always get its turn). Prints one PASS or FAIL line per check.
 */
public final class CheckRunner {

    private final PrintStream out;

    public CheckRunner(PrintStream out) {
        this.out = out;
    }

    public List<CheckResult> runAll(List<Check> checks, SmokeContext ctx) {
        List<CheckResult> results = new ArrayList<>();
        int n = 0;
        for (Check check : checks) {
            n++;
            CheckResult r = runOne(check, ctx);
            results.add(r);
            out.printf("%s %2d. %-28s %s (%d ms)%n", r.passed() ? "PASS" : "FAIL", n, r.name(), r.detail(),
                r.millis());
        }
        long passed = results.stream().filter(CheckResult::passed).count();
        out.println(passed + " passed, " + (results.size() - passed) + " failed");
        return results;
    }

    private static CheckResult runOne(Check check, SmokeContext ctx) {
        long t0 = System.nanoTime();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<String> f = pool.submit(() -> check.run(ctx));
            try {
                String detail = f.get(check.timeout().toMillis(), TimeUnit.MILLISECONDS);
                return new CheckResult(check.name(), true, detail == null ? "" : detail, since(t0));
            } catch (TimeoutException e) {
                f.cancel(true);
                return new CheckResult(check.name(), false, "timed out after " + check.timeout().toSeconds()
                    + " s", since(t0));
            } catch (ExecutionException e) {
                return new CheckResult(check.name(), false, describe(e.getCause()), since(t0));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                f.cancel(true);
                return new CheckResult(check.name(), false, "interrupted", since(t0));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** A failure shows its message; anything else shows its class and message, never a stack trace. */
    private static String describe(Throwable t) {
        if (t instanceof CheckFailure) {
            return t.getMessage();
        }
        String message = t.getMessage();
        return "unexpected " + t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static long since(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** 0 only when there is at least one check and every one passed. */
    public static int exitCode(List<CheckResult> results) {
        return !results.isEmpty() && results.stream().allMatch(CheckResult::passed) ? 0 : 1;
    }
}
