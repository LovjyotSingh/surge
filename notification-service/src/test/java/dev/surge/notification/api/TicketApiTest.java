package dev.surge.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.common.messaging.TicketRequested;
import dev.surge.notification.support.IntegrationTest;
import dev.surge.notification.support.TicketTestBase;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
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

        byte[] pdf = mvc.perform(get("/api/notifications/tickets/" + orderId + ".pdf").header("X-User-Id", "u-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"surge-ticket-" + orderId + ".pdf\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(pdf).isEqualTo(store.get(orderId).orElseThrow());
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        mvc.perform(get("/api/notifications/tickets/" + orderId + ".pdf").header("X-User-Id", "u-2"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/ticket-not-found"));
        mvc.perform(get("/api/notifications/tickets/" + (orderId + 7000) + ".pdf").header("X-User-Id", "u-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/ticket-not-found"));
    }

    @Test
    void downloadNeedsAValidUserHeader() throws Exception {
        for (String bad : new String[] {null, "", "a b", "../x"}) {
            var request = get("/api/notifications/tickets/1.pdf");
            if (bad != null) {
                request.header("X-User-Id", bad);
            }
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-user"));
        }
    }

    @Test
    void admin403() throws Exception {
        String[] wrong = {null, "", "USER", "SUPERADMIN", "ADMINISTRATOR", "admin", "XADMIN,USER"};
        for (String roles : wrong) {
            var list = get("/api/notifications/admin/dlq");
            var replay = post("/api/notifications/admin/dlq/replay");
            if (roles != null) {
                list.header("X-User-Roles", roles);
                replay.header("X-User-Roles", roles);
            }
            mvc.perform(list).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/forbidden"));
            mvc.perform(replay).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/forbidden"));
        }
        mvc.perform(get("/api/notifications/admin/dlq").header("X-User-Roles", "USER, ADMIN")).andExpect(status().isOk());
        mvc.perform(post("/api/notifications/admin/dlq/replay").header("X-User-Roles", "ADMIN")).andExpect(status().isOk());
    }

    @Test
    void dlqLimitIsValidatedAfterTheRoleCheck() throws Exception {
        for (String limit : new String[] {"0", "-1", "101", "abc"}) {
            mvc.perform(get("/api/notifications/admin/dlq?limit=" + limit).header("X-User-Roles", "ADMIN"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"));
            mvc.perform(post("/api/notifications/admin/dlq/replay?limit=" + limit).header("X-User-Roles", "ADMIN"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/notifications/admin/dlq?limit=101")).andExpect(status().isForbidden());
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
            mvc.perform(get("/api/notifications/admin/dlq?limit=20").header("X-User-Roles", "ADMIN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[?(@.messageId == '" + message.messageId() + "')].orderId").value((int) orderId))
                    .andExpect(jsonPath("$[?(@.messageId == '" + message.messageId() + "')].reason").value("s3 is down"));
            assertThat(queueDepth("tickets.dlq")).isEqualTo(3);
        }
        mvc.perform(get("/api/notifications/admin/dlq?limit=2").header("X-User-Roles", "ADMIN"))
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

        mvc.perform(post("/api/notifications/admin/dlq/replay?limit=2").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(2));

        // The two replayed messages fail validation again and come back; nothing is lost.
        org.awaitility.Awaitility.await().untilAsserted(() -> assertThat(queueDepth("tickets.dlq")).isEqualTo(3));
        assertThat(queueDepth("tickets")).isZero();
    }
}
