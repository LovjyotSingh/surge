package dev.surge.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MessageJsonTest {

    @Test
    void orderConfirmedRoundTrips() {
        var mapper = JsonMapper.builder().build();
        var msg = new OrderConfirmed(UUID.randomUUID(), 7L, "u1", 1L, List.of(10L, 11L),
                new BigDecimal("5998"), Instant.parse("2026-11-12T10:00:00Z"));
        var back = mapper.readValue(mapper.writeValueAsString(msg), OrderConfirmed.class);
        assertThat(back).isEqualTo(msg);
    }

    @Test
    void salesResetRoundTrips() {
        var mapper = JsonMapper.builder().build();
        var msg = new SalesReset(UUID.randomUUID(), 1L, Instant.parse("2026-11-12T10:00:00Z"));
        assertThat(mapper.readValue(mapper.writeValueAsString(msg), SalesReset.class)).isEqualTo(msg);
    }

    @Test
    void eventScheduledRoundTrips() {
        var mapper = JsonMapper.builder().build();
        var msg = new EventScheduled(UUID.randomUUID(), 3L, "Coldplay", "Arena", "Delhi", "Asia/Kolkata",
                Instant.parse("2026-12-01T13:00:00Z"), Instant.parse("2026-11-20T04:30:00Z"),
                List.of(new RowSpec("A", 10, "GOLD"), new RowSpec("B", 12, "SILVER")),
                Instant.parse("2026-11-12T10:00:00Z"));
        var json = mapper.writeValueAsString(msg);
        assertThat(mapper.readValue(json, EventScheduled.class)).isEqualTo(msg);
        assertThat(json).contains("\"rowLabel\":\"A\"").contains("\"eventId\":3");
    }

    @Test
    void rowSpecRoundTrips() {
        var mapper = JsonMapper.builder().build();
        var row = new RowSpec("C", 8, "PLATINUM");
        assertThat(mapper.readValue(mapper.writeValueAsString(row), RowSpec.class)).isEqualTo(row);
    }

    @Test
    void typeConstantsMatchRecordNames() {
        assertThat(MessageTypes.SALES_RESET).isEqualTo(SalesReset.class.getSimpleName());
        assertThat(MessageTypes.EVENT_SCHEDULED).isEqualTo(EventScheduled.class.getSimpleName());
        assertThat(MessageTypes.ORDER_CONFIRMED).isEqualTo(OrderConfirmed.class.getSimpleName());
        assertThat(MessageTypes.TICKET_REQUESTED).isEqualTo(TicketRequested.class.getSimpleName());
    }
}
