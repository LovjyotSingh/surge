package dev.surge.smoke.checks;

import static dev.surge.smoke.SmokeContext.expect;

import dev.surge.smoke.Api;
import dev.surge.smoke.Check;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.Poll;
import dev.surge.smoke.SmokeContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Hold two seats, pay: the order's total must equal the quote the hold gave. First makes sure the event is fresh:
 * if it has fewer than three free seats (a load test leaves it sold out) it is reset as admin, and the catalog must
 * show every seat free before the "before" count is read (the catalog learns of a reset through Kafka).
 */
public final class HoldCheckoutCheck implements Check {

    private final Duration wait;
    private final Duration interval;

    public HoldCheckoutCheck() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(1));
    }

    public HoldCheckoutCheck(Duration wait, Duration interval) {
        this.wait = wait;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "hold-and-checkout";
    }

    @Override
    public Duration timeout() {
        return wait.plusSeconds(60);
    }

    @Override
    public String run(SmokeContext ctx) {
        String token = ctx.requireUserToken();
        boolean reset = false;
        if (ctx.tryFreeSeats(3) == null) {
            ctx.resetEvent();
            reset = true;
        }
        Api.Resp event = Poll.until("the catalog to show every seat of event " + ctx.eventId() + " free", wait,
            interval, () -> {
                Api.Resp r = ctx.api().get("/api/catalog/events/" + ctx.eventId(), null);
                if (r.status() != 200) {
                    Poll.note(r.describe());
                    return null;
                }
                int left = r.json().path("seatsLeft").asInt(-1);
                int total = r.json().path("totalSeats").asInt(-2);
                Poll.note("seatsLeft=" + left + " of " + total);
                return left == total ? r : null;
            });
        ctx.seatsLeftBefore = event.json().path("seatsLeft").asInt();

        List<Long> seats = ctx.freeSeats(2);
        ctx.usedSeats.addAll(seats);
        Api.Resp hold = expect(ctx.hold(token, seats), 201, "hold " + seats);
        String holdId = hold.json().path("holdId").asString("");
        BigDecimal quote = hold.json().path("quotedTotalInr").decimalValue();

        Api.Resp order = expect(ctx.checkout(token, holdId), 201, "checkout");
        BigDecimal total = order.json().path("totalInr").decimalValue();
        if (total.compareTo(quote) != 0) {
            throw new CheckFailure("checkout charged " + total.toPlainString() + " but the hold quoted "
                + quote.toPlainString());
        }
        ctx.orderId = order.json().path("orderId").asLong();
        ctx.quotedTotal = quote;
        return "order " + ctx.orderId + ": seats " + seats + ", charged " + total.toPlainString()
            + " INR = quote" + (reset ? " (event was reset first)" : "");
    }
}
