package dev.surge.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.booking.event.EventRefRepository;
import dev.surge.booking.event.Seat;
import dev.surge.booking.event.SeatRepository;
import dev.surge.booking.event.SeatStatus;
import dev.surge.booking.event.Tier;
import dev.surge.booking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class SchemaSeedTest {

    @Autowired EventRefRepository eventRefRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void seedsThreeEventsWithSeats() {
        assertThat(eventRefRepository.count()).isEqualTo(3);
        assertThat(seatRepository.countByEventIdAndStatus(1L, SeatStatus.AVAILABLE)).isEqualTo(500);
        assertThat(seatRepository.countByEventIdAndStatus(2L, SeatStatus.AVAILABLE)).isEqualTo(100);
        var seats = seatRepository.findByEventIdOrderByRowLabelAscNumberAsc(1L);
        assertThat(seats.getFirst()).extracting(Seat::getRowLabel, Seat::getNumber, Seat::getTier)
                .containsExactly("A", 1, Tier.PLATINUM);
        assertThat(seats.getLast()).extracting(Seat::getRowLabel, Seat::getNumber, Seat::getTier)
                .containsExactly("T", 25, Tier.SILVER);
    }

    @Test
    void seatIdsFollowDisplayOrder() {
        assertSeat(1, 1, "A", 1);
        assertSeat(500, 1, "T", 25);
        assertSeat(501, 2, "A", 1);
        assertSeat(700, 3, "D", 25);
    }

    private void assertSeat(long id, long eventId, String row, int number) {
        var s = seatRepository.findById(id).orElseThrow();
        assertThat(s).extracting(Seat::getEventId, Seat::getRowLabel, Seat::getNumber)
                .containsExactly(eventId, row, number);
    }

    @Test
    void orderItemSeatIsUnique() {
        jdbc.update("DELETE FROM order_item WHERE seat_id = 1");
        jdbc.update("DELETE FROM orders WHERE user_id = 'uniq-test'");
        try {
            for (String key : new String[] {"k1", "k2"}) {
                jdbc.update("INSERT INTO orders (user_id, event_id, status, total_inr, idempotency_key, created_at)"
                        + " VALUES ('uniq-test', 1, 'CONFIRMED', 100, ?, NOW(6))", key);
            }
            var ids = jdbc.queryForList("SELECT id FROM orders WHERE user_id = 'uniq-test' ORDER BY id", Long.class);
            jdbc.update("INSERT INTO order_item (order_id, seat_id, price_inr) VALUES (?, 1, 100)", ids.get(0));
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO order_item (order_id, seat_id, price_inr) VALUES (?, 1, 100)", ids.get(1)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM order_item WHERE seat_id = 1");
            jdbc.update("DELETE FROM orders WHERE user_id = 'uniq-test'");
        }
    }
}
