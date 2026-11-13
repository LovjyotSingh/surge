package dev.surge.booking.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id")
    private String userId;
    @Column(name = "event_id")
    private Long eventId;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    @Column(name = "total_inr")
    private BigDecimal totalInr;
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    @Column(name = "created_at")
    private Instant createdAt;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    public Order(String userId, Long eventId, OrderStatus status, BigDecimal totalInr,
                 String idempotencyKey, Instant createdAt) {
        this.userId = userId;
        this.eventId = eventId;
        this.status = status;
        this.totalInr = totalInr;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public Long getEventId() { return eventId; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public BigDecimal getTotalInr() { return totalInr; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public List<OrderItem> getItems() { return items; }
}
