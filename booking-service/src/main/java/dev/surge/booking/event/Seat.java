package dev.surge.booking.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "seat")
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id")
    private Long eventId;
    private String section;
    @Column(name = "row_label")
    private String rowLabel;
    private int number;
    @Enumerated(EnumType.STRING)
    private Tier tier;
    @Enumerated(EnumType.STRING)
    private SeatStatus status;
    @Version
    private long version;

    protected Seat() {}

    public Seat(Long eventId, String section, String rowLabel, int number, Tier tier, SeatStatus status) {
        this.eventId = eventId;
        this.section = section;
        this.rowLabel = rowLabel;
        this.number = number;
        this.tier = tier;
        this.status = status;
    }

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public String getSection() { return section; }
    public String getRowLabel() { return rowLabel; }
    public int getNumber() { return number; }
    public Tier getTier() { return tier; }
    public SeatStatus getStatus() { return status; }
    public void setStatus(SeatStatus status) { this.status = status; }
    public long getVersion() { return version; }
}
