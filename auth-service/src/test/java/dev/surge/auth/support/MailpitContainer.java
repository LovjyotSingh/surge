package dev.surge.auth.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Mailpit (SMTP on 1025, REST API on 8025), as notification-service's tests use it, plus a lookup by recipient. */
public class MailpitContainer extends GenericContainer<MailpitContainer> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    public MailpitContainer() {
        super("axllent/mailpit:v1.33");
        withExposedPorts(1025, 8025);
        waitingFor(Wait.forHttp("/livez").forPort(8025));
    }

    public String smtpHost() {
        return getHost();
    }

    public int smtpPort() {
        return getMappedPort(1025);
    }

    private String api(String path) {
        return "http://" + getHost() + ":" + getMappedPort(8025) + "/api/v1" + path;
    }

    /** Every caught message summary, newest first. */
    public List<JsonNode> messages() {
        JsonNode body = JSON.readTree(send(HttpRequest.newBuilder(URI.create(api("/messages?limit=500"))).GET()
                .build()));
        List<JsonNode> out = new ArrayList<>();
        body.get("messages").forEach(out::add);
        return out;
    }

    /** All caught messages to {@code address}, newest first (summaries). */
    public List<JsonNode> messagesTo(String address) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode m : messages()) {
            for (JsonNode to : m.get("To")) {
                if (address.equalsIgnoreCase(to.get("Address").asString())) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** The full newest message to {@code address}, waiting up to five seconds for it to arrive. */
    public Optional<JsonNode> latestTo(String address, int atLeast) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<JsonNode> found = messagesTo(address);
            if (found.size() >= atLeast) {
                return Optional.of(message(found.get(0).get("ID").asString()));
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public JsonNode message(String id) {
        return JSON.readTree(send(HttpRequest.newBuilder(URI.create(api("/message/" + id))).GET().build()));
    }

    private String send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
