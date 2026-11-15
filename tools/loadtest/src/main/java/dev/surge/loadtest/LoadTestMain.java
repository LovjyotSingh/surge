package dev.surge.loadtest;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.random.RandomGenerator;
import tools.jackson.databind.JsonNode;

public final class LoadTestMain {

    private LoadTestMain() {
    }

    public static void main(String[] args) throws Exception {
        LoadTestConfig cfg;
        try {
            cfg = LoadTestConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(LoadTestConfig.USAGE);
            System.exit(2);
            return;
        }
        System.exit(run(cfg));
    }

    /**
     * Idempotency key for one buyer in one run. A fresh run id per run means a re-run never reuses the keys of an
     * earlier run (whose orders a reset has deleted). A UUID (36) + "-" + the user id stays under the server's
     * 64-character limit: minted ids are "lt-" + 8 hex + an optional batch suffix ("b" + up to 2 digits) + "-" + an
     * index below 2000, so the worst case is a 24-character user id and a 61-character key.
     */
    static String idempotencyKey(String runId, String userId) {
        return runId + "-" + userId;
    }

    static int run(LoadTestConfig cfg) throws Exception {
        return run(cfg, System.out, System.err);
    }

    /** Exit codes: 0 pass, 1 verdict failed, 2 the run could not start (no password, login or minting failed). */
    static int run(LoadTestConfig cfg, PrintStream out, PrintStream err) throws Exception {
        if (cfg.adminPassword() == null || cfg.adminPassword().isEmpty()) {
            err.println("Set " + LoadTestConfig.PASSWORD_ENV + " or pass --admin-password");
            return 2;
        }
        try {
            return execute(cfg, out, err);
        } catch (SetupException e) {
            err.println("ERROR: " + e.getMessage());
            return 2;
        }
    }

    private static int execute(LoadTestConfig cfg, PrintStream out, PrintStream err) throws Exception {
        String runId = UUID.randomUUID().toString();
        try (BookingApi api = new BookingApi(cfg.baseUrl())) {
            api.login(cfg.adminEmail(), cfg.adminPassword());
            if (cfg.reset()) {
                api.reset(cfg.eventId());
            }
            List<Long> seatIds = api.seatIds(cfg.eventId());
            out.println("Run " + runId + ": " + cfg.users() + " buyers, event " + cfg.eventId()
                + ", Idempotency-Key = " + idempotencyKey(runId, "<userId>"));
            // One synthetic user per buyer, minted as late as possible: test tokens live only 15 minutes, so the whole
            // run (from here to the last buyer) must finish within 15 minutes.
            List<BookingApi.TestToken> buyers = api.mintTokens("lt-" + runId.substring(0, 8), cfg.users());
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<BuyerOutcome>> futures = new ArrayList<>(cfg.users());
            Instant startedAt = Instant.now();
            long t0 = System.nanoTime();
            int succeeded = 0;
            int conflicts = 0;
            int rateLimited = 0;
            int errors = 0;
            int unauthorized = 0;
            List<Long> holdLat = new ArrayList<>();
            List<Long> checkoutLat = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int n = 0; n < cfg.users(); n++) {
                    BookingApi.TestToken buyer = buyers.get(n);
                    futures.add(pool.submit(new Buyer(buyer.token(), idempotencyKey(runId, buyer.userId()),
                        cfg.eventId(), seatIds, api, gate, cfg.hotShare(), cfg.maxAttempts(),
                        RandomGenerator.getDefault())));
                }
                gate.countDown();
                for (Future<BuyerOutcome> f : futures) {
                    BuyerOutcome o = f.get();
                    if (o.bought()) {
                        succeeded++;
                    }
                    conflicts += o.conflicts();
                    rateLimited += o.rateLimited();
                    errors += o.errors();
                    unauthorized += o.unauthorized();
                    holdLat.addAll(o.holdLatenciesMs());
                    checkoutLat.addAll(o.checkoutLatenciesMs());
                }
            }
            long durationMs = (System.nanoTime() - t0) / 1_000_000;
            if (unauthorized > 0) {
                err.println("ERROR: the gateway rejected " + unauthorized
                    + " buyer tokens (expired, or load-test subjects not enabled)");
                return 2;
            }

            JsonNode integrity = api.integrity(cfg.eventId());
            LoadTestReport report = new LoadTestReport(startedAt, cfg.users(), cfg.eventId(),
                integrity.get("totalSeats").asInt(), integrity.get("soldSeats").asInt(),
                integrity.get("oversold").asInt(), integrity.path("consistent").asBoolean(false), succeeded,
                holdLat.size() + checkoutLat.size(), conflicts, rateLimited, errors,
                durationMs, LatencyStats.of(holdLat), LatencyStats.of(checkoutLat));

            // Show the result and save the local files first: a failing publish must not lose a finished run.
            out.println(ReportWriter.markdown(report));
            writeFiles(cfg.outDir(), report, out, err);
            try {
                api.putReport(ReportWriter.json(report));
            } catch (RuntimeException e) {
                err.println("ERROR: the report could not be sent to the Ops page: " + e.getMessage());
                return 2;
            }
            return report.passed() ? 0 : 1;
        }
    }

    /** Saving the files is a convenience: a failure is reported but does not fail the run. */
    private static void writeFiles(Path dir, LoadTestReport report, PrintStream out, PrintStream err) {
        try {
            List<Path> files = ReportWriter.write(dir, report);
            out.println("Report files: " + files);
        } catch (IOException e) {
            err.println("WARN: could not write the report files to " + dir + " (" + e + ").");
            err.println("WARN: the report above was printed, so the run is not lost.");
            err.println("WARN: hint for Linux: the container user (uid 10001 unless UID is exported) may not be "
                + "allowed to write ./reports. Run `chmod o+w reports`, or `export UID` before docker compose "
                + "so the container runs as you.");
        }
    }
}
