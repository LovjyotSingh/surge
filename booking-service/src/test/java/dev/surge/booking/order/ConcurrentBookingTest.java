package dev.surge.booking.order;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.booking.error.SeatConflictException;
import dev.surge.booking.error.SeatsTakenException;
import dev.surge.booking.event.SeatRepository;
import dev.surge.booking.event.SeatStatus;
import dev.surge.booking.hold.HoldService;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Headline claim: 2,000 simultaneous buyers, 100 seats, exactly 100 sold and no seat sold twice. */
@IntegrationTest
class ConcurrentBookingTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentBookingTest.class);
    private static final long EVENT_ID = 2L;
    private static final int BUYERS = 2000;
    private static final int SEATS = 100;

    @Autowired TestData testData;
    @Autowired HoldService holdService;
    @Autowired CheckoutService checkoutService;
    @Autowired SeatRepository seatRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void reset() {
        testData.resetEvent(EVENT_ID);
    }

    @Test
    @Timeout(120)
    void twoThousandBuyersHundredSeatsNoOversell() throws Exception {
        List<Long> seatIds = jdbc.queryForList("SELECT id FROM seat WHERE event_id = ? ORDER BY id", Long.class, EVENT_ID);
        assertThat(seatIds).hasSize(SEATS);

        var start = new CountDownLatch(1);
        long t0 = System.nanoTime();
        long bought = 0;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = IntStream.range(0, BUYERS).mapToObj(i -> pool.submit(() -> {
                start.await();
                String user = "load-" + i;
                for (int attempt = 0; attempt < 10; attempt++) {
                    long seat = seatIds.get(ThreadLocalRandom.current().nextInt(seatIds.size()));
                    try {
                        var h = holdService.hold(user, EVENT_ID, List.of(seat));
                        checkoutService.checkout(user, "k-" + i, h.holdId());
                        return true;
                    } catch (SeatsTakenException | SeatConflictException e) {
                        // retry another seat
                    }
                }
                return false;
            })).toList();
            start.countDown();
            for (var f : results) {
                if (f.get()) {
                    bought++;
                }
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        log.info("Concurrency proof: {} buyers, {} bought, {} ms", BUYERS, bought, elapsedMs);

        assertThat(bought).isEqualTo(SEATS);
        assertThat(jdbc.queryForObject("select count(*) from order_item oi join orders o on o.id=oi.order_id where o.event_id=2", Long.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(distinct seat_id) from order_item oi join orders o on o.id=oi.order_id where o.event_id=2", Long.class)).isEqualTo(100);
        assertThat(seatRepository.countByEventIdAndStatus(EVENT_ID, SeatStatus.SOLD)).isEqualTo(100);
    }
}
