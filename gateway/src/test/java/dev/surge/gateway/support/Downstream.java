package dev.surge.gateway.support;

import java.io.IOException;
import java.net.BindException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;

/** A MockWebServer standing in for one downstream service: records requests, answers 200 JSON unless told otherwise. */
public final class Downstream {

    private static final Function<RecordedRequest, MockResponse> OK = request -> new MockResponse.Builder().code(200)
            .addHeader("Content-Type", "application/json").body("{\"ok\":true}").build();

    private final BlockingQueue<RecordedRequest> seen = new LinkedBlockingQueue<>();
    private volatile Function<RecordedRequest, MockResponse> handler = OK;
    private MockWebServer server;
    private int port;

    public Downstream start() throws IOException {
        return start(0);
    }

    /**
     * Starts listening, on {@code requestedPort} or (0) any free port. A restart on the old port is retried for up to
     * 10 s: on Linux the stopped server's socket can still be closing for a moment ("Address already in use", seen in
     * CI), and a restart that gave up would leave this service down for every later test in the shared context.
     */
    private Downstream start(int requestedPort) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            MockWebServer candidate = new MockWebServer();
            candidate.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    seen.add(request);
                    return handler.apply(request);
                }
            });
            try {
                candidate.start(requestedPort);
                server = candidate;
                port = candidate.getPort();
                return this;
            } catch (BindException e) {
                candidate.close();
                if (requestedPort == 0 || System.nanoTime() > deadline) {
                    throw e;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    public String url() {
        return "http://localhost:" + port;
    }

    public Function<RecordedRequest, MockResponse> currentHandler() {
        return handler;
    }

    public void respondWith(Function<RecordedRequest, MockResponse> response) {
        handler = response;
    }

    /** Forget recorded requests and restore the default answer. Call before each test. */
    public void reset() {
        seen.clear();
        handler = OK;
    }

    /** The next request this server received, or null when none arrives within five seconds. */
    public RecordedRequest next() throws InterruptedException {
        return seen.poll(5, TimeUnit.SECONDS);
    }

    public boolean sawNothing() throws InterruptedException {
        return seen.poll(300, TimeUnit.MILLISECONDS) == null;
    }

    /** Stops listening: later connections to this port are refused, like a service that is down. */
    public void stop() {
        server.close();
    }

    /** Listens again on the same port (a fresh server, so recorded requests are not carried over). */
    public void restart() throws IOException {
        start(port);
    }
}
