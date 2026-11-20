package dev.surge.smoke.checks;

import dev.surge.smoke.Api;
import dev.surge.smoke.Check;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.SmokeContext;
import java.util.List;

/**
 * Rapid holds from one user end in a 429 (the gateway's per-user bucket allows a burst of 10). The holds name a seat
 * that does not exist, so none of them takes a real seat or counts towards the three-holds-per-user cap.
 */
public final class RateLimitCheck implements Check {

    static final int HOLDS = 14;
    private static final long NO_SUCH_SEAT = 999_999_999L;

    @Override
    public String name() {
        return "rapid-holds-get-429";
    }

    @Override
    public String run(SmokeContext ctx) {
        String token = ctx.requireUserToken();
        int limited = 0;
        int firstLimited = 0;
        String problem = "";
        StringBuilder statuses = new StringBuilder();
        for (int i = 1; i <= HOLDS; i++) {
            Api.Resp r = ctx.hold(token, List.of(NO_SUCH_SEAT));
            statuses.append(i == 1 ? "" : ",").append(r.status());
            if (r.status() == 429) {
                limited++;
                if (firstLimited == 0) {
                    firstLimited = i;
                    problem = r.problem();
                }
            }
        }
        if (limited == 0) {
            throw new CheckFailure("no 429 in " + HOLDS + " rapid holds (statuses " + statuses + ")");
        }
        return limited + " of " + HOLDS + " rapid holds got 429" + (problem.isEmpty() ? "" : " " + problem)
            + ", the first at hold " + firstLimited;
    }
}
