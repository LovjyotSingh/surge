package dev.surge.gateway.support;

import java.io.IOException;
import java.net.BindException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
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

    /**
     * Listens on a free port BELOW the operating system's ephemeral range (Linux 32768-60999, Windows 49152-65535).
     * {@link #restart()} must get the same port back after {@link #stop()}, because the gateway's routes point at it.
     * A port the kernel picked itself ("any free port") comes from the ephemeral range, and once freed the kernel can
     * hand it to one of the gateway's own outgoing connections, which then holds it: on the Linux CI runner the
     * re-bind failed with "Address already in use" for longer than ten seconds and every later test in the shared
     * context saw this service down. Ports the kernel never gives to outgoing connections avoid that.
     */
    public Downstream start() throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            int candidate = 20_000 + ThreadLocalRandom.current().nextInt(12_000);
            try {
                return start(candidate, false);
            } catch (BindException taken) {
                // in use, or reserved by the OS (Windows excluded port ranges): try another
            }
        }
        throw new IOException("no free port found in 20000-31999");
    }

    private Downstream start(int requestedPort) throws IOException {
        return start(requestedPort, true);
    }

    /**
     * Starts listening on {@code requestedPort}. With {@code retry}, a bind refused as "Address already in use" is
     * retried for up to 10 s (the stopped server's socket can still be closing for a moment).
     */
    private Downstream start(int requestedPort, boolean retry) throws IOException {
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
                if (!retry || System.nanoTime() > deadline) {
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
