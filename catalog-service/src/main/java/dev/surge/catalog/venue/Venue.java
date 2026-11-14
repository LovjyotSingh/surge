package dev.surge.catalog.venue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "venue")
public class Venue {

    @Id
    private Long id;
    private String name;
    private String city;
    @Column(name = "zone_id")
    private String zoneId;
    private BigDecimal latitude;
    private BigDecimal longitude;

    protected Venue() {
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public String getZoneId() { return zoneId; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }
}
