package dev.surge.notification.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** Marks a message id as handled by a consumer; the primary key makes a second delivery a no-op. */
@Entity
@Table(name = "processed_message")
public class ProcessedMessage {

    @EmbeddedId
    private ProcessedMessageId id;
    @Column(name = "processed_at")
    private Instant processedAt;

    protected ProcessedMessage() {
    }

    public ProcessedMessageId getId() {
        return id;
    }

    public String getMessageId() {
        return id.messageId();
    }

    public String getConsumer() {
        return id.consumer();
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
