package dev.surge.smoke.checks;

import dev.surge.smoke.Api;
import dev.surge.smoke.Check;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.SmokeContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Edge and identity controls. Every control is tried even when an earlier one failed, and all failures are listed:
 * an anonymous admin reset, a forged X-User-Id, a forged "lt-" token, security headers on the page, no-store on the
 * API, and logout revoking the token.
 *
 * <p>"lt-" buyers: on the base stack nobody can get a validly signed one, because the admin mint endpoint is not
 * registered (404; that is the control checked). The hand-made unsigned lt- token is kept as a cheap check that the
 * gateway verifies signatures; it does not prove the gateway's own lt- subject rule.
 */
public final class SecurityCheck implements Check {

    private static final List<String> PAGE_HEADERS = List.of("X-Frame-Options", "X-Content-Type-Options",
        "Referrer-Policy", "Permissions-Policy", "Cross-Origin-Opener-Policy");

    @Override
    public String name() {
        return "security-controls";
    }

    @Override
    public String run(SmokeContext ctx) {
        String email = ctx.email;
        String password = ctx.password;
        if (email == null || password == null) {
            throw new CheckFailure("needs the signup check to have passed first (no user to log out)");
        }
        List<String> problems = new ArrayList<>();
        Api api = ctx.api();

        Api.Resp reset = api.post("/api/bookings/admin/events/" + ctx.eventId() + "/reset?opensInSeconds=0", null,
            null);
        if (reset.status() != 401) {
            problems.add("anonymous reset: expected 401 but got " + reset.describe());
        }

        Api.Resp forgedId = api.send("GET", "/api/bookings/orders/1", null, Map.of("X-User-Id", "u-1"), null);
        if (forgedId.status() != 401) {
            problems.add("forged X-User-Id without a token: expected 401 but got " + forgedId.describe());
        }

        Api.Resp forgedToken = api.get("/api/bookings/orders/1", unsignedLoadTestToken());
        if (forgedToken.status() != 401) {
            problems.add("forged lt- token: expected 401 but got " + forgedToken.describe());
        }

        // The test-token controller is not even registered unless SURGE_AUTH_TEST_TOKENS is set (only the load-test
        // overlay does that), so an admin asking for synthetic lt- buyers gets a plain 404 on this stack.
        Api.Resp mint = api.post("/api/auth/admin/test-tokens", ctx.adminToken(),
            "{\"prefix\":\"lt-smoke\",\"count\":1}");
        if (mint.status() != 404) {
            problems.add("admin test-tokens (lt- buyers): expected 404 because minting is off, got "
                + mint.describe() + " (is compose.loadtest.yaml in use?)");
        }

        Api.Resp page = api.get("/", null);
        List<String> missing = PAGE_HEADERS.stream().filter(h -> page.header(h).isEmpty()).toList();
        if (page.status() != 200 || !missing.isEmpty()) {
            problems.add("security headers on /: HTTP " + page.status() + ", missing " + missing);
        }

        Api.Resp catalog = api.get("/api/catalog/events/" + ctx.eventId(), null);
        if (!catalog.header("Cache-Control").contains("no-store")) {
            problems.add("Cache-Control no-store missing on /api/ (got \"" + catalog.header("Cache-Control") + "\")");
        }

        // A fresh login, so the user's working token stays valid for the checks after this one.
        String token = ctx.login(email, password);
        Api.Resp logout = api.post("/api/auth/logout", token, null);
        if (logout.status() != 204) {
            problems.add("logout: expected 204 but got " + logout.describe());
        } else {
            Api.Resp after = api.get("/api/auth/me", token);
            if (after.status() != 401) {
                problems.add("logout: the token still works afterwards (" + after.describe() + ")");
            }
        }

        if (!problems.isEmpty()) {
            throw new CheckFailure(String.join("; ", problems));
        }
        return "anonymous reset 401, forged X-User-Id 401, forged lt- token 401, lt- minting off (404), page headers ok, /api no-store, "
            + "logout revokes the token";
    }

    /** {"alg":"none"} . {"sub":"lt-smoke","lt":true} . (no signature) */
    private static String unsignedLoadTestToken() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64.encodeToString(
            "{\"sub\":\"lt-smoke\",\"lt\":true,\"iss\":\"surge-auth\"}".getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + ".";
    }
}
