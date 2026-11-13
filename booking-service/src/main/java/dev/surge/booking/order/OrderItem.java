package dev.surge.booking.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "order_item")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;
    @Column(name = "seat_id")
    private Long seatId;
    @Column(name = "price_inr")
    private BigDecimal priceInr;

    protected OrderItem() {}

    public OrderItem(Long seatId, BigDecimal priceInr) {
        this.seatId = seatId;
        this.priceInr = priceInr;
    }

    public Long getId() { return id; }
    public Order getOrder() { return order; }
    void setOrder(Order order) { this.order = order; }
    public Long getSeatId() { return seatId; }
    public BigDecimal getPriceInr() { return priceInr; }
}
