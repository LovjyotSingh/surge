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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Thin HTTP wrapper over the booking service. Not Spring-aware. */
public final class BookingApi implements AutoCloseable {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String base;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .executor(executor)
        .build();

    public BookingApi(URI baseUrl) {
        String s = baseUrl.toString();
        this.base = s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public void reset(long eventId) {
        HttpResponse<String> r = send(builder("/api/bookings/admin/events/" + eventId + "/reset?opensInSeconds=0")
            .POST(HttpRequest.BodyPublishers.noBody()));
        requireStatus(r, 204, "reset");
    }

    public List<Long> seatIds(long eventId) {
        HttpResponse<String> r = send(builder("/api/bookings/events/" + eventId).GET());
        requireStatus(r, 200, "event detail");
        List<Long> ids = new ArrayList<>();
        for (JsonNode seat : JSON.readTree(r.body()).get("seats")) {
            ids.add(seat.get("id").asLong());
        }
        return ids;
    }

    public HttpResponse<String> hold(String user, long eventId, long seatId) {
        String body = "{\"eventId\":" + eventId + ",\"seatIds\":[" + seatId + "]}";
        return send(builder("/api/bookings/holds")
            .header("X-User-Id", user)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    public HttpResponse<String> checkout(String user, String key, String holdId) {
        String body = "{\"holdId\":\"" + holdId + "\"}";
        return send(builder("/api/bookings/orders")
            .header("X-User-Id", user)
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    public JsonNode integrity(long eventId) {
        HttpResponse<String> r = send(builder("/api/bookings/admin/events/" + eventId + "/integrity").GET());
        requireStatus(r, 200, "integrity");
        return JSON.readTree(r.body());
    }

    public void putReport(String json) {
        HttpResponse<String> r = send(builder("/api/bookings/admin/loadtest-reports/latest")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json)));
        requireStatus(r, 204, "put report");
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(REQUEST_TIMEOUT);
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
