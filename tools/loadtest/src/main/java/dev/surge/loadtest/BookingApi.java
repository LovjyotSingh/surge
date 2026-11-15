package dev.surge.loadtest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Thin HTTP wrapper over the gateway. Not Spring-aware.
 *
 * <p>Buyers authenticate with their own bearer token. Admin calls (reset, integrity, report) use the admin token
 * from {@link #login}; if it is rejected with 401 (it lives 60 minutes) the admin signs in again once and the call is
 * retried. No request ever carries an {@code X-User-Id} header: the gateway sets identity from the token. Tokens and
 * the password are never put into exception messages.
 */
public final class BookingApi implements AutoCloseable {

    /** The auth service's {@code @Max} on {@code count} for one test-token request. */
    static final int MAX_TOKENS_PER_CALL = 20000;

    /** A minted buyer: the user id the gateway will see (the token's subject) and its bearer token. */
    public record TestToken(String userId, String token) {
        @Override
        public String toString() {
            return "TestToken[userId=" + userId + ", token=***]";
        }
    }

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String base;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .executor(executor)
        .build();
    private volatile String adminToken;
    private String adminEmail;
    private String adminPassword;

    public BookingApi(URI baseUrl) {
        String s = baseUrl.toString();
        this.base = s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Signs in as the admin and remembers the token (and the credentials, for one re-login on expiry). */
    public void login(String email, String password) {
        this.adminEmail = email;
        this.adminPassword = password;
        signIn();
    }

    private void signIn() {
        String body = JSON.writeValueAsString(Map.of("email", adminEmail, "password", adminPassword));
        HttpResponse<String> r = send(builder("/api/auth/login")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() != 200) {
            throw new SetupException("admin login failed (HTTP " + r.statusCode() + ")");
        }
        JsonNode token = JSON.readTree(r.body()).get("token");
        if (token == null || token.asString().isBlank()) {
            throw new SetupException("admin login failed (no token in the response)");
        }
        adminToken = token.asString();
    }

    /** Mints {@code count} buyer tokens; asks for at most {@link #MAX_TOKENS_PER_CALL} per request. */
    public List<TestToken> mintTokens(String prefix, int count) {
        return mintTokens(prefix, count, MAX_TOKENS_PER_CALL);
    }

    /**
     * Each batch needs its own prefix, because the server names users {@code prefix-0 .. prefix-(n-1)}: batch 0 uses
     * {@code prefix}, batch k uses {@code prefix + "b" + k} (still alphanumeric, within the 16-character limit).
     */
    List<TestToken> mintTokens(String prefix, int count, int maxPerCall) {
        List<TestToken> all = new ArrayList<>(count);
        int batch = 0;
        while (all.size() < count) {
            int n = Math.min(maxPerCall, count - all.size());
            String batchPrefix = batch == 0 ? prefix : prefix + "b" + batch;
            all.addAll(mintBatch(batchPrefix, n));
            batch++;
        }
        return all;
    }

    private List<TestToken> mintBatch(String prefix, int count) {
        String body = JSON.writeValueAsString(Map.of("prefix", prefix, "count", count));
        HttpResponse<String> r = adminSend(() -> builder("/api/auth/admin/test-tokens")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
        int status = r.statusCode();
        if (status == 403 || status == 404) {
            throw new SetupException("test tokens are disabled on this deployment "
                + "(enable them only for load tests)");
        }
        if (status == 401) {
            throw new SetupException("admin login failed (token rejected)");
        }
        if (status != 200) {
            throw new SetupException("minting test tokens failed (HTTP " + status + ")");
        }
        List<TestToken> out = new ArrayList<>(count);
        for (JsonNode t : JSON.readTree(r.body()).get("tokens")) {
            out.add(new TestToken(t.get("userId").asString(), t.get("token").asString()));
        }
        if (out.size() != count) {
            throw new IllegalStateException("minting returned " + out.size() + " tokens, expected " + count);
        }
        return out;
    }

    public void reset(long eventId) {
        HttpResponse<String> r = adminSend(() -> builder(
            "/api/bookings/admin/events/" + eventId + "/reset?opensInSeconds=0")
            .POST(HttpRequest.BodyPublishers.noBody()));
        requireStatus(r, 204, "reset");
    }

    /** Public read: no credentials needed. */
    public List<Long> seatIds(long eventId) {
        HttpResponse<String> r = send(builder("/api/bookings/events/" + eventId).GET());
        requireStatus(r, 200, "event detail");
        List<Long> ids = new ArrayList<>();
        for (JsonNode seat : JSON.readTree(r.body()).get("seats")) {
            ids.add(seat.get("id").asLong());
        }
        return ids;
    }

    public HttpResponse<String> hold(String bearer, long eventId, long seatId) {
        String body = "{\"eventId\":" + eventId + ",\"seatIds\":[" + seatId + "]}";
        return send(builder("/api/bookings/holds")
            .header("Authorization", "Bearer " + bearer)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    public HttpResponse<String> checkout(String bearer, String key, String holdId) {
        String body = "{\"holdId\":\"" + holdId + "\"}";
        return send(builder("/api/bookings/orders")
            .header("Authorization", "Bearer " + bearer)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    public JsonNode integrity(long eventId) {
        HttpResponse<String> r = adminSend(() -> builder("/api/bookings/admin/events/" + eventId + "/integrity")
            .GET());
        requireStatus(r, 200, "integrity");
        return JSON.readTree(r.body());
    }

    public void putReport(String json) {
        HttpResponse<String> r = adminSend(() -> builder("/api/bookings/admin/loadtest-reports/latest")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json)));
        requireStatus(r, 204, "put report");
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(REQUEST_TIMEOUT);
    }

    /** Sends with the admin token; on 401 signs in once more and retries once. */
    private HttpResponse<String> adminSend(Supplier<HttpRequest.Builder> request) {
        if (adminToken == null) {
            throw new IllegalStateException("not logged in as admin");
        }
        HttpResponse<String> r = send(request.get().header("Authorization", "Bearer " + adminToken));
        if (r.statusCode() == 401) {
            signIn();
            r = send(request.get().header("Authorization", "Bearer " + adminToken));
        }
        return r;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return client.send(request.build(), BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private static void requireStatus(HttpResponse<String> r, int expected, String what) {
        if (r.statusCode() != expected) {
            throw new IllegalStateException(what + " failed: HTTP " + r.statusCode() + " " + r.body());
        }
    }

    @Override
    public void close() {
        client.close();
        executor.close();
    }
}
