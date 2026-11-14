package dev.surge.catalog.event;

import dev.surge.catalog.venue.Venue;
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
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "event")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private String artist;
    private String category;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id")
    private Venue venue;
    @Column(name = "starts_at")
    private Instant startsAt;
    @Column(name = "sale_opens_at")
    private Instant saleOpensAt;
    private String description;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout_json", columnDefinition = "json")
    private String layoutJson;
    @Column(name = "min_price_inr")
    private BigDecimal minPriceInr;
    @Column(name = "max_price_inr")
    private BigDecimal maxPriceInr;
    @Column(name = "total_seats")
    private int totalSeats;
    @Column(name = "seats_left")
    private int seatsLeft;
    @Column(name = "scheduled_published")
    private boolean scheduledPublished;

    protected Event() {
    }

    public Event(String title, String artist, String category, Venue venue, Instant startsAt, Instant saleOpensAt,
                 String description, String layoutJson, BigDecimal minPriceInr, BigDecimal maxPriceInr,
                 int totalSeats) {
        this.title = title;
        this.artist = artist;
        this.category = category;
        this.venue = venue;
        this.startsAt = startsAt;
        this.saleOpensAt = saleOpensAt;
        this.description = description;
        this.layoutJson = layoutJson;
        this.minPriceInr = minPriceInr;
        this.maxPriceInr = maxPriceInr;
        this.totalSeats = totalSeats;
        this.seatsLeft = totalSeats;
    }

    public void markScheduledPublished() {
        this.scheduledPublished = true;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getCategory() { return category; }
    public Venue getVenue() { return venue; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getSaleOpensAt() { return saleOpensAt; }
    public String getDescription() { return description; }
    public String getLayoutJson() { return layoutJson; }
    public BigDecimal getMinPriceInr() { return minPriceInr; }
    public BigDecimal getMaxPriceInr() { return maxPriceInr; }
    public int getTotalSeats() { return totalSeats; }
    public int getSeatsLeft() { return seatsLeft; }
    public boolean isScheduledPublished() { return scheduledPublished; }
}
