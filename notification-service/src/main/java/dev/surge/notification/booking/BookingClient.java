package dev.surge.notification.booking;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * Reads an order from booking-service. Timeouts and I/O errors propagate, so the caller retries.
 *
 * <p>KNOWN BROKEN until S3: booking no longer reads {@code X-User-Id} (S2b) and answers this unsigned call with 401.
 * S3 replaces it with booking's {@code GET /internal/orders/{id}?userId=...}, signed as {@code svc:notification}.
 */
public class BookingClient {

    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final RestClient client;

    public BookingClient(RestClient client) {
        this.client = client;
    }

    public BookingOrder order(String userId, long orderId) {
        // The id goes into a header: reject anything but the id alphabet (no CR/LF, no spaces) before calling out.
        if (userId == null || !VALID_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("invalid user id");
        }
        BookingOrder order = client.get().uri("/api/bookings/orders/{id}", orderId).header("X-User-Id", userId)
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
