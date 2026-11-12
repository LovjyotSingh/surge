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
}
