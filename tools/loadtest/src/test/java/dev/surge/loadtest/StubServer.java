package dev.surge.loadtest;

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

/** Minimal scripted HTTP stub: per "METHOD path" a queue of canned responses (the last one repeats). */
final class StubServer implements AutoCloseable {

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply of(int status, String body) {
            return new Reply(status, body, Map.of());
        }
    }

    private final HttpServer server;
    private final Map<String, Deque<Reply>> scripts = new ConcurrentHashMap<>();
    final List<String> received = new CopyOnWriteArrayList<>();
    final List<String> receivedBodies = new CopyOnWriteArrayList<>();

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    StubServer on(String methodAndPath, Reply... replies) {
        Deque<Reply> q = new ArrayDeque<>();
        for (Reply r : replies) {
            q.add(r);
        }
        scripts.put(methodAndPath, q);
        return this;
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange ex) throws IOException {
        String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(key);
        receivedBodies.add(body);
        Deque<Reply> q = scripts.get(key);
        Reply reply;
        if (q == null) {
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
