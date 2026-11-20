package dev.surge.smoke;

import dev.surge.smoke.checks.AuthFlowCheck;
import dev.surge.smoke.checks.CatalogAuditCheck;
import dev.surge.smoke.checks.CleanupCheck;
import dev.surge.smoke.checks.ForgedRolesCheck;
import dev.surge.smoke.checks.HoldCheckoutCheck;
import dev.surge.smoke.checks.PaymentChaosCheck;
import dev.surge.smoke.checks.RateLimitCheck;
import dev.surge.smoke.checks.SearchCheck;
import dev.surge.smoke.checks.SecurityCheck;
import dev.surge.smoke.checks.TicketCheck;
import java.util.List;

/**
 * The checks in the order they run: later ones use what earlier ones leave in the {@link SmokeContext}. The
 * order also keeps the gateway's rate buckets (signup 3/min, holds 20/min per IP) from getting in the way.
 */
public final class Checks {

    private Checks() {
    }

    public static List<Check> all() {
        return List.of(
            new AuthFlowCheck(),
            new SearchCheck(),
            new HoldCheckoutCheck(),
            new TicketCheck(),
            new CatalogAuditCheck(),
            new PaymentChaosCheck(),
            new RateLimitCheck(),
            new ForgedRolesCheck(),
            new SecurityCheck(),
            new CleanupCheck());
    }
}
