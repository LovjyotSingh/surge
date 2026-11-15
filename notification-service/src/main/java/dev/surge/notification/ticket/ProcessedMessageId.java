package dev.surge.notification.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;

/** A message id is unique per consumer, not globally: the same id may be claimed once by each consumer. */
@Embeddable
public record ProcessedMessageId(
        @Column(name = "consumer", length = 64) String consumer,
        @Column(name = "message_id", length = 36) String messageId) implements Serializable {
}
