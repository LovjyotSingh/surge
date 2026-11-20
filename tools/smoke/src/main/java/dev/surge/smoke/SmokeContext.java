package dev.surge.smoke;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * What the checks share: the two HTTP clients (the stack through nginx, and the Mailpit API) and the state one check
 * leaves for the next (the new user, their token, the order). A check that needs state an earlier check was meant to
 * leave fails with a clear message instead of a NullPointerException.
 */
public final class SmokeContext implements AutoCloseable {

    private final SmokeConfig config;
    private final Api gateway;
    private final Api mail;
    private volatile String adminToken;

    // State left by earlier checks.
    public volatile String email;
    public volatile String password;
    public volatile String userToken;
    public volatile Long orderId;
    public volatile BigDecimal quotedTotal;
    public volatile Integer seatsLeftBefore;
    public final Set<Long> usedSeats = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private SmokeContext(SmokeConfig config) {
        this.config = config;
        this.gateway = new Api(config.baseUrl());
        this.mail = new Api(config.mailUrl());
    }

    public static SmokeContext of(SmokeConfig config) {
        return new SmokeContext(config);
    }

    public SmokeConfig config() {
        return config;
    }

    public Api api() {
        return gateway;
    }

    public Api mail() {
        return mail;
    }

    public long eventId() {
        return config.eventId();
    }

    /** The admin's bearer token; signs in on first use. Tokens live 30 minutes, longer than a run. */
    public synchronized String adminToken() {
        if (adminToken == null) {
            adminToken = login(config.adminEmail(), config.adminPassword());
        }
        return adminToken;
    }

    /** POST /api/auth/login; the token, or a {@link CheckFailure} that never names the password. */
    public String login(String email, String password) {
        Api.Resp r = gateway.post("/api/auth/login", null,
            Api.JSON.writeValueAsString(Map.of("email", email, "password", password)));
        expect(r, 200, "login as " + email);
        String token = r.json().path("token").asString("");
        if (token.isBlank()) {
            throw new CheckFailure("login as " + email + ": no token in the response");
        }
        return token;
    }

    public String requireUserToken() {
        String t = userToken;
        if (t == null) {
            throw new CheckFailure("needs the signup check to have passed first (no user token)");
        }
        return t;
    }

    public long requireOrderId() {
        Long id = orderId;
        if (id == null) {
            throw new CheckFailure("needs the checkout check to have passed first (no order)");
        }
        return id;
    }

    /** Fails with a one-line description unless {@code r} has the status. */
    public static Api.Resp expect(Api.Resp r, int status, String what) {
        if (r.status() != status) {
            throw new CheckFailure(what + ": expected HTTP " + status + " but got " + r.describe());
        }
        return r;
    }

    /** {@code n} AVAILABLE seats of the event that no check has used yet. */
    public List<Long> freeSeats(int n) {
        List<Long> out = tryFreeSeats(n);
        if (out == null) {
            throw new CheckFailure("event " + eventId() + " has fewer than " + n + " free seat(s) "
                + "(reset it first: POST /api/bookings/admin/events/" + eventId() + "/reset?opensInSeconds=0)");
        }
        return out;
    }

    /** Like {@link #freeSeats} but null (not a failure) when the event has fewer than {@code n} such seats. */
    public List<Long> tryFreeSeats(int n) {
        Api.Resp r = expect(gateway.get("/api/bookings/events/" + eventId(), null), 200, "event detail");
        List<Long> out = new ArrayList<>();
        for (JsonNode seat : r.json().path("seats")) {
            long id = seat.path("id").asLong();
            if ("AVAILABLE".equals(seat.path("state").asString("")) && !usedSeats.contains(id)) {
                out.add(id);
                if (out.size() == n) {
                    return out;
                }
            }
        }
        return null;
    }

    /** Admin reset of the event: all seats free, sale open now. */
    public void resetEvent() {
        expect(gateway.post("/api/bookings/admin/events/" + eventId() + "/reset?opensInSeconds=0", adminToken(),
            null), 204, "reset event " + eventId());
    }

    /** The state of one seat ("AVAILABLE", "HELD", "SOLD") or "" when it is not on the map. */
    public String seatState(long seatId) {
        Api.Resp r = expect(gateway.get("/api/bookings/events/" + eventId(), null), 200, "event detail");
        for (JsonNode seat : r.json().path("seats")) {
            if (seat.path("id").asLong() == seatId) {
                return seat.path("state").asString("");
            }
        }
        return "";
    }

    public Api.Resp hold(String bearer, List<Long> seatIds) {
        String body = "{\"eventId\":" + eventId() + ",\"seatIds\":" + seatIds + "}";
        return gateway.post("/api/bookings/holds", bearer, body);
    }

    /** Checkout with a fresh Idempotency-Key. */
    public Api.Resp checkout(String bearer, String holdId) {
        return gateway.send("POST", "/api/bookings/orders", bearer,
            Map.of("Idempotency-Key", "smoke-" + UUID.randomUUID()), "{\"holdId\":\"" + holdId + "\"}");
    }

    /** Puts the payment chaos settings (admin). */
    public void setChaos(double errorRate, int latencyMs) {
        String body = "{\"failureRate\":0,\"latencyMs\":" + latencyMs + ",\"errorRate\":" + errorRate + "}";
        expect(gateway.put("/api/payments/admin/chaos", adminToken(), body), 204, "set payment chaos");
    }

    @Override
    public void close() {
        gateway.close();
        mail.close();
    }
}
