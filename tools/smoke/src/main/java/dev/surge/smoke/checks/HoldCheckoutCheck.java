package dev.surge.smoke.checks;

import static dev.surge.smoke.SmokeContext.expect;

import dev.surge.smoke.Api;
import dev.surge.smoke.Check;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.SmokeContext;
import java.math.BigDecimal;
import java.util.List;

/** Hold two seats, pay: the order's total must equal the quote the hold gave. */
public final class HoldCheckoutCheck implements Check {

    @Override
    public String name() {
        return "hold-and-checkout";
    }

    @Override
    public String run(SmokeContext ctx) {
        String token = ctx.requireUserToken();
        Api.Resp event = expect(ctx.api().get("/api/catalog/events/" + ctx.eventId(), null), 200, "catalog event");
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
            + " INR = quote";
    }
}
