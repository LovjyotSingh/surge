package dev.surge.smoke.checks;

import dev.surge.smoke.Api;
import dev.surge.smoke.Check;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.SmokeContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Rapid holds from one user end in a 429 (the gateway's per-user bucket allows a burst of 10, then refills 2 a
 * second). The holds are sent at the same moment from separate threads, so a slow machine cannot space them out
 * enough to stay under the limit. They name a seat that does not exist, so none takes a real seat or counts towards
 * the three-holds-per-user cap.
 */
public final class RateLimitCheck implements Check {

    static final int HOLDS = 14;
    private static final long NO_SUCH_SEAT = 999_999_999L;

    @Override
    public String name() {
        return "rapid-holds-get-429";
    }

    @Override
    public String run(SmokeContext ctx) throws InterruptedException, ExecutionException {
        String token = ctx.requireUserToken();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Api.Resp>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < HOLDS; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return ctx.hold(token, List.of(NO_SUCH_SEAT));
                }));
            }
            go.countDown();
        }
        int limited = 0;
        String problem = "";
        StringBuilder statuses = new StringBuilder();
        for (Future<Api.Resp> f : futures) {
            Api.Resp r = f.get();
            statuses.append(statuses.isEmpty() ? "" : ",").append(r.status());
            if (r.status() == 429) {
                limited++;
                problem = r.problem();
            }
        }
        if (limited == 0) {
            throw new CheckFailure("no 429 in " + HOLDS + " simultaneous holds (statuses " + statuses + ")");
        }
        return limited + " of " + HOLDS + " simultaneous holds got 429" + (problem.isEmpty() ? "" : " " + problem);
    }
}
