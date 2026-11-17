package dev.surge.notification.ticket;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Everything printed on a ticket. {@code startsAt} is already in the venue's zone. {@code eventId} is not printed; it is
 * bound into the QR's signature ({@link TicketQr}).
 */
public record TicketData(long orderId, String userId, long eventId, String eventTitle, String venueName,
                         ZonedDateTime startsAt, List<String> seats, BigDecimal totalInr) {
}
