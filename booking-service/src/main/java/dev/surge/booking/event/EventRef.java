package dev.surge.booking.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "event_ref")
public class EventRef {
    @Id
    private Long id;
    private String title;
    @Column(name = "venue_name")
    private String venueName;
    @Column(name = "sale_opens_at")
    private Instant saleOpensAt;
    @Column(name = "zone_id")
    private String zoneId;
    @Enumerated(EnumType.STRING)
    private EventStatus status;

    protected EventRef() {}

    public EventRef(Long id, String title, String venueName, Instant saleOpensAt, String zoneId, EventStatus status) {
        this.id = id;
        this.title = title;
        this.venueName = venueName;
        this.saleOpensAt = saleOpensAt;
        this.zoneId = zoneId;
        this.status = status;
    }

    /** Applies a re-announced event: only descriptive fields change, never seats or status. */
    public void updateDetails(String title, String venueName, Instant saleOpensAt, String zoneId) {
        this.title = title;
        this.venueName = venueName;
        this.saleOpensAt = saleOpensAt;
        this.zoneId = zoneId;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getVenueName() { return venueName; }
    public Instant getSaleOpensAt() { return saleOpensAt; }
    public String getZoneId() { return zoneId; }
    public EventStatus getStatus() { return status; }
}
