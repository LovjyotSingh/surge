package dev.surge.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.common.messaging.TicketRequested;
import dev.surge.notification.support.Identity;
import dev.surge.notification.support.IntegrationTest;
import dev.surge.notification.support.TicketTestBase;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class TicketApiTest extends TicketTestBase {

    @Autowired
    MockMvc mvc;

    private void deadLetter(String body, String reason) {
        MessageBuilderSupport<Message> builder = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON);
        if (reason != null) {
            builder.setHeader("x-exception-message", reason);
        }
        Message message = builder.build();
        rabbit.send("surge.tickets.dlx", "", message);
    }

    @Test
    void ownerCanDownloadOthersGet404() throws Exception {
        long orderId = nextOrderId();
        TicketRequested message = stubOrder("u-1", orderId);
        assertThat(workflow.process(message)).isTrue();

        byte[] pdf = mvc.perform(get("/api/notifications/tickets/" + orderId + ".pdf").with(Identity.user("u-1")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"surge-ticket-" + orderId + ".pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(pdf).isEqualTo(store.get(orderId).orElseThrow());
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        mvc.perform(get("/api/notifications/tickets/" + orderId + ".pdf").with(Identity.user("u-2")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/ticket-not-found"));
        mvc.perform(get("/api/notifications/tickets/" + (orderId + 7000) + ".pdf").with(Identity.user("u-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/ticket-not-found"));
    }

    /**
     * SEC-21: a ticket holds personal data, so no browser or proxy may keep it (and nothing may sniff it into
     * something else). It is always a download, never rendered inline.
     */
    @Test
    void theTicketIsANoStoreAttachment() throws Exception {
        long orderId = nextOrderId();
        assertThat(workflow.process(stubOrder("u-1", orderId))).isTrue();

        var response = mvc.perform(get("/api/notifications/tickets/" + orderId + ".pdf").with(Identity.user("u-1")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"surge-ticket-" + orderId + ".pdf\""))
                .andReturn().getResponse();
        assertThat(response.getHeaders("Cache-Control")).hasSize(1).first().asString().contains("no-store")
                .doesNotContain("public");
    }

    @Test
    void downloadNeedsASignedInUser() throws Exception {
        // the gateway's anonymous token is no user, and a forged legacy header next to it changes nothing
        for (String forged : new String[] {null, "u-1"}) {
            var request = get("/api/notifications/tickets/1.pdf").with(Identity.anonymous());
            if (forged != null) {
                request.header("X-User-Id", forged);
            }
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-user"));
        }
        // without a token the identity chain answers before any controller
        mvc.perform(get("/api/notifications/tickets/1.pdf").header("X-User-Id", "u-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/unauthorized"));
    }

    @Test
    void admin403() throws Exception {
        // a lower-case "admin" cannot even be signed: the gateway drops such roles
        String[][] wrong = {{}, {"USER"}, {"SUPERADMIN"}, {"ADMINISTRATOR"}, {"XADMIN", "USER"}};
        for (String[] roles : wrong) {
            var list = get("/api/notifications/admin/dlq").with(Identity.userWithRoles("u-1", roles));
            var replay = post("/api/notifications/admin/dlq/replay").with(Identity.userWithRoles("u-1", roles));
            mvc.perform(list).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/forbidden"));
            mvc.perform(replay).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/forbidden"));
        }
        mvc.perform(get("/api/notifications/admin/dlq").with(Identity.admin())).andExpect(status().isOk());
        mvc.perform(post("/api/notifications/admin/dlq/replay").with(Identity.admin())).andExpect(status().isOk());
    }

    @Test
    void dlqLimitIsValidatedAfterTheRoleCheck() throws Exception {
        for (String limit : new String[] {"0", "-1", "101", "abc"}) {
            mvc.perform(get("/api/notifications/admin/dlq?limit=" + limit).with(Identity.admin()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"));
            mvc.perform(post("/api/notifications/admin/dlq/replay?limit=" + limit).with(Identity.admin()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/notifications/admin/dlq?limit=101").with(Identity.user("u-1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void dlqListDoesNotConsume() throws Exception {
        long orderId = nextOrderId();
        TicketRequested message = stubOrder("u-1", orderId);
        deadLetter(JSON.writeValueAsString(message), "s3 is down");
        deadLetter(JSON.writeValueAsString(stubOrder("u-2", nextOrderId())), null);
        deadLetter("{garbage", null);
        org.awaitility.Awaitility.await().untilAsserted(() -> assertThat(queueDepth("tickets.dlq")).isEqualTo(3));

        for (int round = 0; round < 2; round++) {
            mvc.perform(get("/api/notifications/admin/dlq?limit=20").with(Identity.admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[?(@.messageId == '" + message.messageId() + "')].orderId").value((int) orderId))
                    .andExpect(jsonPath("$[?(@.messageId == '" + message.messageId() + "')].reason").value("s3 is down"));
            assertThat(queueDepth("tickets.dlq")).isEqualTo(3);
        }
        mvc.perform(get("/api/notifications/admin/dlq?limit=2").with(Identity.admin()))
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(queueDepth("tickets.dlq")).isEqualTo(3);
    }

    @Test
    void replayMovesOnlyTheRequestedNumber() throws Exception {
        for (int i = 0; i < 3; i++) {
            long id = nextOrderId();
            // Invalid on purpose (empty seats): replayed messages go straight back to the DLQ, so the count is stable.
            deadLetter(JSON.writeValueAsString(new TicketRequested(java.util.UUID.randomUUID(), id, "u-1", 3L,
                    java.util.List.of(), java.time.Instant.EPOCH)), "bad");
        }
        org.awaitility.Awaitility.await().untilAsserted(() -> assertThat(queueDepth("tickets.dlq")).isEqualTo(3));

        mvc.perform(post("/api/notifications/admin/dlq/replay?limit=2").with(Identity.admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(2));

        // The two replayed messages fail validation again and come back; nothing is lost.
        org.awaitility.Awaitility.await().untilAsserted(() -> assertThat(queueDepth("tickets.dlq")).isEqualTo(3));
        assertThat(queueDepth("tickets")).isZero();
    }

    @Autowired
    org.springframework.amqp.rabbit.core.RabbitAdmin admin;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("ticketsBinding")
    org.springframework.amqp.core.Binding ticketsBinding;

    @Test
    void replayOfAnUnroutableMessageKeepsItInTheDlq() throws Exception {
        deadLetter(JSON.writeValueAsString(stubOrder("u-1", nextOrderId())), "s3 is down");
        org.awaitility.Awaitility.await().untilAsserted(() -> assertThat(queueDepth("tickets.dlq")).isEqualTo(1));

        admin.removeBinding(ticketsBinding);
        try {
            mvc.perform(post("/api/notifications/admin/dlq/replay?limit=5").with(Identity.admin()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/replay-failed"));
        } finally {
            admin.declareBinding(ticketsBinding);
        }

        org.awaitility.Awaitility.await().untilAsserted(() -> assertThat(queueDepth("tickets.dlq")).isEqualTo(1));
        assertThat(queueDepth("tickets")).isZero();
        // The binding is restored in the finally block above; the message stays for the next test's purge.
    }

    /** SEC-19: one ADMIN_ACTION line per admin action, with the verified admin and the request id; no message bodies. */
    @Test
    void everyAdminActionWritesOneAuditLine(CapturedOutput output) throws Exception {
        // invalid on purpose (no seats): the replayed message comes straight back to the DLQ
        deadLetter(JSON.writeValueAsString(new TicketRequested(java.util.UUID.randomUUID(), nextOrderId(),
                "u-secret-owner", 3L, java.util.List.of(), java.time.Instant.EPOCH)), "bad");
        org.awaitility.Awaitility.await().untilAsserted(() -> assertThat(queueDepth("tickets.dlq")).isEqualTo(1));

        mvc.perform(get("/api/notifications/admin/dlq?limit=5").with(Identity.admin()).header("X-Request-Id", "rid-l"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/notifications/admin/dlq/replay?limit=1").with(Identity.admin())
                .header("X-Request-Id", "rid-r")).andExpect(status().isOk());
        org.awaitility.Awaitility.await().untilAsserted(() -> assertThat(queueDepth("tickets.dlq")).isEqualTo(1));
        mvc.perform(get("/api/notifications/admin/audit?date=2026-11-14&tail=5").with(Identity.admin())
                .header("X-Request-Id", "rid-a")).andExpect(status().isOk());
        // refused by validation: no action, no line
        mvc.perform(get("/api/notifications/admin/dlq?limit=0").with(Identity.admin())).andExpect(status().isBadRequest());

        assertThat(output.getAll().lines().filter(l -> l.contains("ADMIN_ACTION"))
                .map(l -> l.substring(l.indexOf("ADMIN_ACTION"))).toList()).containsExactly(
                "ADMIN_ACTION actor=u-admin action=notification.dlq.list target=queue:tickets.dlq requestId=rid-l",
                "ADMIN_ACTION actor=u-admin action=notification.dlq.replay target=queue:tickets.dlq,replayed:1 "
                        + "requestId=rid-r",
                "ADMIN_ACTION actor=u-admin action=notification.audit.tail target=audit:2026-11-14 requestId=rid-a");
    }
}
