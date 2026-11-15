package dev.surge.notification.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.notification.support.IntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class ProcessedMessagePerConsumerTest {

    @Autowired ProcessedMessageRepository processed;
    @Autowired TransactionTemplate tx;

    private int claim(String id, String consumer) {
        return tx.execute(status -> processed.claim(id, consumer, Instant.now()));
    }

    @Test
    void theSameMessageIdCanBeClaimedOncePerConsumer() {
        String id = UUID.randomUUID().toString();

        assertThat(claim(id, "tickets")).isEqualTo(1);
        assertThat(claim(id, "audit")).isEqualTo(1);
        assertThat(claim(id, "audit")).isZero();
        assertThat(claim(id, "tickets")).isZero();

        assertThat(processed.findById(new ProcessedMessageId("audit", id))).isPresent();
        assertThat(processed.findById(new ProcessedMessageId("other", id))).isEmpty();
    }
}
