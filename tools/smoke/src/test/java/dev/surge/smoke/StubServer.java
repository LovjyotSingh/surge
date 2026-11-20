package dev.surge.smoke;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Minimal scripted HTTP stub: per "METHOD path" a queue of canned responses (the last one repeats). */
public final class StubServer implements AutoCloseable {

    public record Reply(int status, String body, Map<String, String> headers) {
        public static Reply of(int status, String body) {
            return new Reply(status, body, Map.of());
        }

        public static Reply of(int status, String body, Map<String, String> headers) {
            return new Reply(status, body, headers);
        }
    }

    private final HttpServer server;
    private final Map<String, Deque<Reply>> scripts = new ConcurrentHashMap<>();
    private final Map<String, Function<String, Reply>> dynamic = new ConcurrentHashMap<>();
    /** Authorization header of each request, or "" when absent (same index as {@link #received}). */
    public final List<String> receivedAuth = new CopyOnWriteArrayList<>();
    /** True per request when an X-User-Id header was present (same index as {@link #received}). */
    public final List<Boolean> receivedUserIdHeader = new CopyOnWriteArrayList<>();
    public final List<String> received = new CopyOnWriteArrayList<>();
    public final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    /** Raw query string of each request, "" when none (same index as {@link #received}). */
    public final List<String> receivedQueries = new CopyOnWriteArrayList<>();
    /** The X-User-Roles header of each request, "" when absent (same index as {@link #received}). */
    public final List<String> receivedRoles = new CopyOnWriteArrayList<>();
    /** Idempotency-Key header of each request, or "" when absent (same index as {@link #received}). */
    public final List<String> receivedIdempotencyKeys = new CopyOnWriteArrayList<>();

    public StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public StubServer on(String methodAndPath, Reply... replies) {
        Deque<Reply> q = new ArrayDeque<>();
        for (Reply r : replies) {
            q.add(r);
        }
        dynamic.remove(methodAndPath);
        scripts.put(methodAndPath, q);
        return this;
    }

    /** The reply is computed from the request body. */
    public StubServer onBody(String methodAndPath, Function<String, Reply> reply) {
        dynamic.put(methodAndPath, reply);
        return this;
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange ex) throws IOException {
        String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String idem = ex.getRequestHeaders().getFirst("Idempotency-Key");
        synchronized (this) { // keep the three lists index-aligned under concurrent requests
            received.add(key);
            receivedBodies.add(body);
            receivedIdempotencyKeys.add(idem == null ? "" : idem);
            String query = ex.getRequestURI().getRawQuery();
            receivedQueries.add(query == null ? "" : query);
            String roles = ex.getRequestHeaders().getFirst("X-User-Roles");
            receivedRoles.add(roles == null ? "" : roles);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            receivedAuth.add(auth == null ? "" : auth);
            receivedUserIdHeader.add(ex.getRequestHeaders().containsKey("X-User-Id"));
        }
        Deque<Reply> q = scripts.get(key);
        Reply reply;
        if (dynamic.containsKey(key)) {
            reply = dynamic.get(key).apply(body);
        } else if (q == null) {
            reply = Reply.of(404, "");
        } else {
            synchronized (q) {
                reply = q.size() > 1 ? q.poll() : q.peek();
            }
        }
        reply.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            ex.sendResponseHeaders(reply.status(), -1);
        } else {
            ex.sendResponseHeaders(reply.status(), bytes.length);
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
