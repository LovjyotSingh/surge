package dev.surge.notification.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ticket")
public class Ticket {

    @Id
    @Column(name = "order_id")
    private long orderId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "event_id", nullable = false)
    private long eventId;

    @Column(name = "s3_key", nullable = false, length = 200)
    private String s3Key;

    @Column(name = "emailed_at")
    private Instant emailedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Ticket() {
    }

    public Ticket(long orderId, String userId, long eventId, String s3Key, Instant createdAt) {
        this.orderId = orderId;
        this.userId = userId;
        this.eventId = eventId;
        this.s3Key = s3Key;
        this.createdAt = createdAt;
    }

    public long getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public long getEventId() {
        return eventId;
    }

    public String getS3Key() {
        return s3Key;
    }

    public Instant getEmailedAt() {
        return emailedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void markEmailed(Instant at) {
        this.emailedAt = at;
    }
}
