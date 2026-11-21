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
 * from {@link #login}; if it is rejected with 401 (it lives 30 minutes) the admin signs in again once and the call is
 * retried. No request ever carries an {@code X-User-Id} header: the gateway sets identity from the token. Tokens and
 * the password are never put into exception messages.
 */
public final class BookingApi implements AutoCloseable {

    /** The auth service's {@code @Max} on {@code count} for one test-token request. */
    static final int MAX_TOKENS_PER_CALL = 20000;

    /**
     * Tokens per mint request. The server needs about 1 s per 1000 tokens (signing) and the gateway cuts a response off
     * after 30 s, so one 20000-token request is too close to the limit; the hard cap stays {@link #MAX_TOKENS_PER_CALL}.
     */
    static final int MINT_BATCH = 2000;

    /** "lt-" plus at most 16 alphanumeric characters (the server pattern). */
    private static final int MAX_PREFIX_LENGTH = 19;

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

    /** Mints {@code count} buyer tokens; asks for {@link #MINT_BATCH} per request. */
    public List<TestToken> mintTokens(String prefix, int count) {
        return mintTokens(prefix, count, MINT_BATCH);
    }

    /**
     * Each batch needs its own prefix, because the server names users {@code prefix-0 .. prefix-(n-1)}: batch 0 uses
     * {@code prefix}, batch k uses {@code prefix + "b" + k} (still alphanumeric, within the 16-character limit).
     */
    List<TestToken> mintTokens(String prefix, int count, int maxPerCall) {
        List<TestToken> all = new ArrayList<>(count);
        int batch = 0;
        while (all.size() < count) {
            int n = Math.min(Math.min(maxPerCall, MAX_TOKENS_PER_CALL), count - all.size());
            String batchPrefix = batch == 0 ? prefix : prefix + "b" + batch;
            if (batchPrefix.length() > MAX_PREFIX_LENGTH) {
                throw new SetupException("too many buyers to mint under one prefix");
            }
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
                + "(enable them only for load tests) "
                + "— or the admin account lacks the ADMIN role");
        }
        if (status == 401) {
            throw new SetupException("admin login failed (token rejected)");
        }
        if (status != 200) {
            throw new SetupException("minting test tokens failed (HTTP " + status + ")");
        }
        List<TestToken> out = new ArrayList<>(count);
        try {
            for (JsonNode t : JSON.readTree(r.body()).get("tokens")) {
                String userId = t.get("userId").asString();
                String token = t.get("token").asString();
                if (userId.isBlank() || token.isBlank()) {
                    throw new SetupException("minting test tokens failed (malformed response)");
                }
                out.add(new TestToken(userId, token));
            }
        } catch (SetupException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SetupException("minting test tokens failed (malformed response)");
        }
        if (out.size() != count) {
            throw new SetupException("minting test tokens failed (got " + out.size() + " tokens, expected "
                + count + ")");
        }
        return out;
    }

    public void reset(long eventId) {
        HttpResponse<String> r = adminSend(() -> builder(
            "/api/bookings/admin/events/" + eventId + "/reset?opensInSeconds=0")
            .POST(HttpRequest.BodyPublishers.noBody()));
        requireStatus(r, 204, "reset");
    }

    /**
     * "GET path: HTTP 503 service-unavailable" while the gateway has no route to the service yet (the problem type ends
     * in service-unavailable; the bulkhead's service-busy is another type), else null, also when unreachable.
     */
    String unavailable(String path) {
        try {
            HttpResponse<String> r = send(builder(path).GET());
            if (r.statusCode() == 503
                && JSON.readTree(r.body()).path("type").asString("").endsWith("service-unavailable")) {
                return "GET " + path + ": HTTP 503 service-unavailable";
            }
        } catch (RuntimeException e) {
            // not "no route yet" (unreachable, or not problem+json): the run reports it
        }
        return null;
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
            throw new IllegalStateException(what + " failed: HTTP " + r.statusCode());
        }
    }

    @Override
    public void close() {
        client.close();
        executor.close();
    }
}
