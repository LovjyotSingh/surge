package dev.surge.loadtest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    static int run(LoadTestConfig cfg) throws Exception {
        try (BookingApi api = new BookingApi(cfg.baseUrl())) {
            if (cfg.reset()) {
                api.reset(cfg.eventId());
            }
            List<Long> seatIds = api.seatIds(cfg.eventId());
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<BuyerOutcome>> futures = new ArrayList<>(cfg.users());
            Instant startedAt = Instant.now();
            long t0 = System.nanoTime();
            int succeeded = 0;
            int conflicts = 0;
            int rateLimited = 0;
            int errors = 0;
            List<Long> holdLat = new ArrayList<>();
            List<Long> checkoutLat = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int n = 0; n < cfg.users(); n++) {
                    futures.add(pool.submit(new Buyer("lt-" + n, cfg.eventId(), seatIds, api, gate,
                        cfg.hotShare(), cfg.maxAttempts(), RandomGenerator.getDefault())));
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
                    holdLat.addAll(o.holdLatenciesMs());
                    checkoutLat.addAll(o.checkoutLatenciesMs());
                }
            }
            long durationMs = (System.nanoTime() - t0) / 1_000_000;

            JsonNode integrity = api.integrity(cfg.eventId());
            LoadTestReport report = new LoadTestReport(startedAt, cfg.users(), cfg.eventId(),
                integrity.get("totalSeats").asInt(), integrity.get("soldSeats").asInt(),
                integrity.get("oversold").asInt(), succeeded, conflicts, rateLimited, errors,
                durationMs, LatencyStats.of(holdLat), LatencyStats.of(checkoutLat));

            ReportWriter.write(cfg.outDir(), report);
            api.putReport(ReportWriter.json(report));
            System.out.println(ReportWriter.markdown(report));
            return report.oversold() > 0 ? 1 : 0;
        }
    }
}
