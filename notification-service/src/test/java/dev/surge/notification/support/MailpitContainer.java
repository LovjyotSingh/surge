package dev.surge.notification.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Mailpit (SMTP on 1025, REST API and UI on 8025) with a few helpers for asserting on the mail it caught. */
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

    public void clear() {
        send(HttpRequest.newBuilder(URI.create(api("/messages"))).DELETE().build());
    }

    /** Every caught message summary, newest first. */
    public List<JsonNode> messages() {
        JsonNode body = JSON.readTree(send(HttpRequest.newBuilder(URI.create(api("/messages"))).GET().build()));
        List<JsonNode> out = new ArrayList<>();
        body.get("messages").forEach(out::add);
        return out;
    }

    public JsonNode message(String id) {
        return JSON.readTree(send(HttpRequest.newBuilder(URI.create(api("/message/" + id))).GET().build()));
    }

    public byte[] attachment(String messageId, String partId) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(api("/message/" + messageId + "/part/" + partId))).GET()
                    .build(), HttpResponse.BodyHandlers.ofByteArray()).body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
