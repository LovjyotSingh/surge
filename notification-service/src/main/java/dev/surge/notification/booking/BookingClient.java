package dev.surge.notification.booking;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * Reads an order from booking-service's {@code GET /internal/orders/{id}?userId=...}, as {@code svc:notification}
 * (the client's signing interceptor, see {@code ClientsConfig}): booking answers only for the order's owner and only
 * to notification. Notification never claims to be the user. Timeouts and I/O errors propagate, so the caller retries.
 */
public class BookingClient {

    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final RestClient client;

    public BookingClient(RestClient client) {
        this.client = client;
    }

    public BookingOrder order(String userId, long orderId) {
        // Reject anything but the id alphabet (no CR/LF, no spaces, no separators) before calling out.
        if (userId == null || !VALID_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("invalid user id");
        }
        BookingOrder order = client.get()
                .uri(builder -> builder.path("/internal/orders/{id}").queryParam("userId", "{userId}")
                        .build(orderId, userId))
                .retrieve()
                .onStatus(s -> s.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                    throw new BookingOrderNotFoundException(orderId);
                })
                .body(BookingOrder.class);
        if (order == null || order.lines() == null || order.lines().isEmpty()) {
            throw new BookingOrderNotFoundException(orderId);
        }
        return order;
    }
}
