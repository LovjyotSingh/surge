package dev.surge.smoke.checks;

import static dev.surge.smoke.SmokeContext.expect;

import dev.surge.smoke.Check;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.SmokeContext;

/** Leaves the stack as found: payment chaos off, and the event reset so the next run (or a load test) starts clean. */
public final class CleanupCheck implements Check {

    @Override
    public String name() {
        return "cleanup";
    }

    @Override
    public String run(SmokeContext ctx) {
        String chaosProblem = null;
        try {
            ctx.setChaos(0.0, 0);
        } catch (RuntimeException e) {
            chaosProblem = "could not switch payment chaos off (" + e.getMessage() + ")";
        }
        // The reset still runs when chaos could not be switched off.
        expect(ctx.api().post("/api/bookings/admin/events/" + ctx.eventId() + "/reset?opensInSeconds=0",
            ctx.adminToken(), null), 204, "reset event " + ctx.eventId());
        if (chaosProblem != null) {
            throw new CheckFailure(chaosProblem);
        }
        return "payment chaos off, event " + ctx.eventId() + " reset";
    }
}
