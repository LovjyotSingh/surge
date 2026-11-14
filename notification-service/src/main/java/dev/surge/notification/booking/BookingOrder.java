package dev.surge.notification.booking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

/** The slice of booking's order view that a ticket needs: the seat labels and the total. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingOrder(long orderId, long eventId, BigDecimal totalInr, List<Line> lines) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(long seatId, String rowLabel, int number, BigDecimal priceInr) {
    }

    /** Seat labels as printed on the ticket, for example "A1". */
    public List<String> seatLabels() {
        return lines.stream().map(l -> l.rowLabel() + l.number()).toList();
    }
}
