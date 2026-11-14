package dev.surge.notification.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.notification.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class TicketRepositoryTest {

    @Autowired
    TicketRepository tickets;

    @Test
    void savesAndLoadsATicket() {
        Instant created = Instant.parse("2027-01-01T10:00:00Z").truncatedTo(ChronoUnit.MICROS);
        tickets.saveAndFlush(new Ticket(555L, "u-9", 3L, "tickets/555.pdf", created));

        Ticket loaded = tickets.findById(555L).orElseThrow();
        assertThat(loaded.getUserId()).isEqualTo("u-9");
        assertThat(loaded.getEventId()).isEqualTo(3L);
        assertThat(loaded.getS3Key()).isEqualTo("tickets/555.pdf");
        assertThat(loaded.getCreatedAt()).isEqualTo(created);
        assertThat(loaded.getEmailedAt()).isNull();
    }
}
