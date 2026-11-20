package dev.surge.smoke.checks;

import dev.surge.smoke.Api;
import dev.surge.smoke.Check;
import dev.surge.smoke.Poll;
import dev.surge.smoke.SmokeContext;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Within 30 s of the order: the catalog's seatsLeft has dropped by the two seats sold (a Kafka consumer), and
 * today's audit log (also fed from Kafka) names the order.
 */
public final class CatalogAuditCheck implements Check {

    private static final int SEATS_BOUGHT = 2;

    private final Duration wait;
    private final Duration interval;

    public CatalogAuditCheck() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(1));
    }

    public CatalogAuditCheck(Duration wait, Duration interval) {
        this.wait = wait;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "catalog-and-audit";
    }

    @Override
    public Duration timeout() {
        return wait.multipliedBy(2).plusSeconds(10);
    }

    @Override
    public String run(SmokeContext ctx) {
        long orderId = ctx.requireOrderId();
        Integer before = ctx.seatsLeftBefore;
        if (before == null) {
            throw new dev.surge.smoke.CheckFailure("needs the checkout check to have passed first (no seatsLeft)");
        }
        int target = before - SEATS_BOUGHT;

        int now = Poll.until("catalog seatsLeft <= " + target, wait, interval, () -> {
            Api.Resp r = ctx.api().get("/api/catalog/events/" + ctx.eventId(), null);
            if (r.status() != 200) {
                Poll.note(r.describe());
                return null;
            }
            int left = r.json().path("seatsLeft").asInt(Integer.MAX_VALUE);
            Poll.note("seatsLeft=" + left);
            return left <= target ? left : null;
        });

        // orderId may sit in a JSON object or inside an escaped JSON string; never match a longer number (77 vs 770).
        Pattern entry = Pattern.compile("orderId\\\\*\"?\\s*[:=]\\s*\\\\*\"?" + orderId + "(?![0-9])");
        Poll.until("an audit log entry for order " + orderId, wait, interval, () -> {
            Api.Resp r = ctx.api().get("/api/notifications/admin/audit?tail=500", ctx.adminToken());
            if (r.status() != 200) {
                Poll.note(r.describe());
                return null;
            }
            return entry.matcher(r.text()).find() ? Boolean.TRUE : null;
        });
        return "catalog seatsLeft " + before + " -> " + now + "; audit log has order " + orderId;
    }
}
