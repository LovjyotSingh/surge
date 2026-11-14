package dev.surge.notification.ticket;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

/** Everything printed on a ticket. {@code startsAt} is already in the venue's zone. */
public record TicketData(long orderId, String userId, String eventTitle, String venueName, ZonedDateTime startsAt,
                         List<String> seats, BigDecimal totalInr) {
}
