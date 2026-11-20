package dev.surge.smoke.checks;

import dev.surge.smoke.Api;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.Poll;
import dev.surge.smoke.SmokeContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;

/** Reads the Mailpit API (dev overlay only): the stack's outgoing mail ends up there. */
final class Mailbox {

    private Mailbox() {
    }

    /**
     * Waits for the newest message to {@code email} that satisfies {@code wanted} and returns its full detail.
     * {@code wanted} sees the entry of the search list ({@code ID}, {@code Subject}, {@code Attachments} count).
     */
    static JsonNode waitForMessage(SmokeContext ctx, String email, String what, Duration timeout,
                                   Duration interval, java.util.function.Predicate<JsonNode> wanted) {
        return Poll.until(what, timeout, interval, () -> {
            String query = URLEncoder.encode("to:" + email, StandardCharsets.UTF_8);
            Api.Resp list = ctx.mail().get("/api/v1/search?query=" + query, null);
            if (list.status() != 200) {
                throw new CheckFailure("Mailpit search: HTTP " + list.status() + " (is the dev overlay running, "
                    + "and is --mail-url right?)");
            }
            JsonNode messages = list.json().path("messages");
            Poll.note(messages.size() + " message(s) for " + email);
            for (JsonNode m : messages) {
                if (wanted.test(m)) {
                    Api.Resp detail = ctx.mail().get("/api/v1/message/" + m.path("ID").asString(""), null);
                    if (detail.status() == 200) {
                        return detail.json();
                    }
                }
            }
            return null;
        });
    }
}
