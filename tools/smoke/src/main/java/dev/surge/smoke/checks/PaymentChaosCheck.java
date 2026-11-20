package dev.surge.smoke.checks;

import static dev.surge.smoke.SmokeContext.expect;

import dev.surge.smoke.Api;
import dev.surge.smoke.Check;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.Poll;
import dev.surge.smoke.SmokeContext;
import java.time.Duration;
import java.util.List;

/**
 * Payment chaos: with errorRate=1 every checkout answers 503 payment-unavailable and the seats stay held; once the
 * breaker has seen enough failures the next call fails fast (circuit open, no payment call). With chaos off and the
 * breaker's wait over (10 s) a checkout succeeds again. Chaos is switched off again whatever happens.
 */
public final class PaymentChaosCheck implements Check {

    /** A call that never reaches payment answers in a few milliseconds; a real failing one takes 100 ms+ (retry). */
    private static final long FAST_MS = 300;

    private final int failures;
    private final Duration recoveryWait;
    private final Duration recoveryDeadline;
    private final Duration interval;

    public PaymentChaosCheck() {
        this(6, Duration.ofSeconds(11), Duration.ofSeconds(25), Duration.ofSeconds(1));
    }

    /**
     * @param failures         checkouts that must all fail before the fast-fail call (the plan: at least 5)
     * @param recoveryWait     pause after chaos is off (the breaker stays open 10 s)
     * @param recoveryDeadline how long checkout may keep failing after that
     * @param interval         pause between calls (the gateway allows 2 checkouts a second per user)
     */
    public PaymentChaosCheck(int failures, Duration recoveryWait, Duration recoveryDeadline, Duration interval) {
        this.failures = failures;
        this.recoveryWait = recoveryWait;
        this.recoveryDeadline = recoveryDeadline;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "payment-chaos-and-circuit";
    }

    @Override
    public Duration timeout() {
        return recoveryWait.plus(recoveryDeadline).plus(interval.multipliedBy(failures + 2L)).plusSeconds(30);
    }

    @Override
    public String run(SmokeContext ctx) throws InterruptedException {
        String token = ctx.requireUserToken();
        List<Long> seat = ctx.freeSeats(1);
        ctx.usedSeats.addAll(seat);

        String holdId;
        long fastMs;
        ctx.setChaos(1.0);
        try {
            Api.Resp hold = expect(ctx.hold(token, seat), 201, "hold " + seat);
            holdId = hold.json().path("holdId").asString("");
            for (int i = 1; i <= failures; i++) {
                requireUnavailable(ctx.checkout(token, holdId), "checkout " + i + " with chaos on");
                Thread.sleep(interval.toMillis());
            }
            Api.Resp fast = ctx.checkout(token, holdId);
            requireUnavailable(fast, "checkout " + (failures + 1) + " (circuit should be open)");
            fastMs = fast.millis();
            if (fastMs >= FAST_MS) {
                throw new CheckFailure("after " + failures + " failures the next checkout took " + fastMs
                    + " ms: the circuit did not fail fast (< " + FAST_MS + " ms)");
            }
            String state = ctx.seatState(seat.get(0));
            if (!"HELD".equals(state)) {
                throw new CheckFailure("seat " + seat.get(0) + " is " + state + " after the failed payments, "
                    + "expected it to stay held");
            }
        } finally {
            switchChaosOff(ctx);
        }

        Thread.sleep(recoveryWait.toMillis());
        long t0 = System.nanoTime();
        Api.Resp paid = Poll.until("checkout to recover after chaos was switched off", recoveryDeadline, interval,
            () -> {
                Api.Resp r = ctx.checkout(token, holdId);
                if (r.status() == 201) {
                    return r;
                }
                Poll.note(r.describe());
                return null;
            });
        long seconds = (System.nanoTime() - t0) / 1_000_000_000;
        return failures + " checkouts answered 503 payment-unavailable, call " + (failures + 1) + " failed fast ("
            + fastMs + " ms), seat stayed held; recovered " + (recoveryWait.toSeconds() + seconds)
            + " s after chaos off (order " + paid.json().path("orderId").asLong() + ")";
    }

    private static void requireUnavailable(Api.Resp r, String what) {
        if (r.status() != 503 || !"payment-unavailable".equals(r.problem())) {
            throw new CheckFailure(what + ": expected 503 payment-unavailable but got " + r.describe());
        }
    }

    /** Never leaves chaos on: a failure here is reported by the cleanup check as well. */
    private static void switchChaosOff(SmokeContext ctx) {
        try {
            ctx.setChaos(0.0);
        } catch (RuntimeException e) {
            System.err.println("WARN: could not switch payment chaos off: " + e.getMessage());
        }
    }
}
