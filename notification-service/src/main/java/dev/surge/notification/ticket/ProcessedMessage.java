package dev.surge.notification.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Marks a message id as handled by a consumer; the primary key makes a second delivery a no-op. */
@Entity
@Table(name = "processed_message")
public class ProcessedMessage {

    @Id
    @Column(name = "message_id", length = 36)
    private String messageId;
    private String consumer;
    @Column(name = "processed_at")
    private Instant processedAt;

    protected ProcessedMessage() {
    }

    public String getMessageId() {
        return messageId;
    }

    public String getConsumer() {
        return consumer;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
