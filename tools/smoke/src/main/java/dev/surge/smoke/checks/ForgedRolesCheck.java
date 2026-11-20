package dev.surge.smoke.checks;

import dev.surge.smoke.Api;
import dev.surge.smoke.Check;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.SmokeContext;
import java.util.Map;

/** A USER token plus a forged {@code X-User-Roles: ADMIN} must not open an admin route. */
public final class ForgedRolesCheck implements Check {

    @Override
    public String name() {
        return "forged-roles-header";
    }

    @Override
    public String run(SmokeContext ctx) {
        String token = ctx.requireUserToken();
        Api.Resp r = ctx.api().send("GET", "/api/bookings/admin/events/" + ctx.eventId() + "/integrity", token,
            Map.of("X-User-Roles", "ADMIN"), null);
        if (r.status() != 403) {
            throw new CheckFailure("admin route with a USER token and a forged X-User-Roles: ADMIN header gave "
                + r.describe() + ", expected 403");
        }
        return "USER token + forged X-User-Roles: ADMIN on an admin route -> 403 " + r.problem();
    }
}
