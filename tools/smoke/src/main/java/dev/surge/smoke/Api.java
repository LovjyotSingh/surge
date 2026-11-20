package dev.surge.smoke;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Thin HTTP wrapper over one base URL. Never throws on a status code; transport problems become {@link CheckFailure}. */
public final class Api implements AutoCloseable {

    public static final ObjectMapper JSON = new ObjectMapper();

    /** One answer: status, raw body bytes, headers and how long the exchange took. */
    public record Resp(int status, byte[] body, HttpHeaders headers, long millis) {

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (RuntimeException e) {
                throw new CheckFailure("HTTP " + status + " but the body is not JSON");
            }
        }

        public String header(String name) {
            return headers.firstValue(name).orElse("");
        }

        /** Last segment of the problem+json {@code type} (e.g. {@code rate-limited}), or "" when there is none. */
        public String problem() {
            try {
                String type = JSON.readTree(body).path("type").asString("");
                return type.substring(type.lastIndexOf('/') + 1);
            } catch (RuntimeException e) {
                return "";
            }
        }

        /** "HTTP 409 seat-taken", for failure messages. */
        public String describe() {
            String p = problem();
            return "HTTP " + status + (p.isEmpty() ? "" : " " + p);
        }
    }

    private final String base;
    private final HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public Api(URI baseUrl) {
        String s = baseUrl.toString();
        this.base = s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public Resp get(String path, String bearer) {
        return send("GET", path, bearer, Map.of(), null);
    }

    public Resp post(String path, String bearer, String jsonBody) {
        return send("POST", path, bearer, Map.of(), jsonBody);
    }

    public Resp put(String path, String bearer, String jsonBody) {
        return send("PUT", path, bearer, Map.of(), jsonBody);
    }

    public Resp send(String method, String path, String bearer, Map<String, String> headers, String jsonBody) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        headers.forEach(b::header);
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        long t0 = System.nanoTime();
        try {
            HttpResponse<byte[]> r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Resp(r.statusCode(), r.body(), r.headers(), (System.nanoTime() - t0) / 1_000_000);
        } catch (IOException e) {
            throw new CheckFailure("cannot reach " + base + path.replaceAll("\\?.*", "") + " ("
                + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CheckFailure("interrupted");
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
