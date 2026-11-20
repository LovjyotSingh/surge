package dev.surge.smoke.checks;

import dev.surge.smoke.Api;
import dev.surge.smoke.Check;
import dev.surge.smoke.CheckFailure;
import dev.surge.smoke.Poll;
import dev.surge.smoke.SmokeContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import tools.jackson.databind.JsonNode;

/** Within 30 s: the ticket email (with a PDF) is in Mailpit, and the signed-in buyer can download ticket.pdf. */
public final class TicketCheck implements Check {

    private final Duration wait;
    private final Duration interval;

    public TicketCheck() {
        this(Duration.ofSeconds(30), Duration.ofSeconds(1));
    }

    public TicketCheck(Duration wait, Duration interval) {
        this.wait = wait;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "ticket-email-and-pdf";
    }

    @Override
    public Duration timeout() {
        return wait.plusSeconds(30);
    }

    @Override
    public String run(SmokeContext ctx) {
        String token = ctx.requireUserToken();
        long orderId = ctx.requireOrderId();
        String email = ctx.email;

        JsonNode message = Mailbox.waitForMessage(ctx, email, "the ticket email", wait, interval,
            m -> m.path("Attachments").asInt(0) > 0);
        boolean pdfAttached = false;
        for (JsonNode a : message.path("Attachments")) {
            String file = a.path("FileName").asString("").toLowerCase(java.util.Locale.ROOT);
            if (file.endsWith(".pdf") || a.path("ContentType").asString("").contains("pdf")) {
                pdfAttached = true;
            }
        }
        if (!pdfAttached) {
            throw new CheckFailure("the ticket email has no PDF attachment");
        }

        Api.Resp pdf = Poll.until("ticket " + orderId + " to be downloadable", wait, interval, () -> {
            Api.Resp r = ctx.api().get("/api/notifications/tickets/" + orderId + ".pdf", token);
            if (r.status() == 404 || r.status() == 429) {
                Poll.note(r.describe());
                return null;
            }
            return r;
        });
        if (pdf.status() != 200) {
            throw new CheckFailure("ticket.pdf: expected HTTP 200 but got " + pdf.describe());
        }
        byte[] head = Arrays.copyOf(pdf.body(), Math.min(4, pdf.body().length));
        if (!"%PDF".equals(new String(head, StandardCharsets.ISO_8859_1))) {
            throw new CheckFailure("ticket.pdf does not start with %PDF");
        }
        return "ticket email with pdf for " + email + "; download " + orderId + ".pdf is %PDF ("
            + pdf.body().length + " bytes)";
    }
}
