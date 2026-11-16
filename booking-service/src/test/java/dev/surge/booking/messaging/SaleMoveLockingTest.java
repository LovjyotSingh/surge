package dev.surge.booking.messaging;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import dev.surge.common.messaging.EventScheduled;
import dev.surge.common.messaging.RowSpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SOLD-seat guard against a checkout committing at the same moment, as two real MySQL transactions. A checkout
 * marks a seat SOLD by primary key (as OrderWriter's JPA update does); the guard's locking read must either wait for
 * that sale and count it, or make a sale that starts after it wait until the move has committed. A plain
 * (snapshot) read would do neither: the forged move would go through while a seat is being sold.
 */
@IntegrationTest
class SaleMoveLockingTest {

    private static final long EVENT = 43L;
    private static final Instant SALE = Instant.parse("2026-11-14T11:00:00Z");
    private static final Instant MOVED = SALE.plusSeconds(86_400);
    /** Long enough to tell "waiting for a lock" from "done"; both transactions finish in milliseconds otherwise. */
    private static final long BLOCKED_MILLIS = 800;

    @Autowired EventScheduledHandler handler;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TestData testData;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        testData.deleteEvent(EVENT);
        jdbc.update("DELETE FROM processed_message WHERE consumer = 'booking-catalog'");
    }

    @Test
    void aSaleThatIsCommittingIsWaitedForAndCounted() throws Exception {
        long seat = createEvent();
        CountDownLatch sold = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try {
            CompletableFuture<Void> checkout = CompletableFuture.runAsync(() -> inTransaction(() -> {
                markSold(seat);
                sold.countDown();
                await(commit);
            }));
            assertThat(sold.await(10, SECONDS)).isTrue();

            CompletableFuture<Void> move = CompletableFuture.runAsync(() -> handler.handle(scheduled(MOVED)));
            assertBlocked(move);

            commit.countDown();
            checkout.get(10, SECONDS);
            assertThatThrownBy(() -> move.get(10, SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has sold seats");
            assertThat(saleOpensAt()).isEqualTo(SALE);
        } finally {
            commit.countDown();
        }
    }

    @Test
    void aSaleThatStartsAfterTheCheckWaitsForTheMove() throws Exception {
        long seat = createEvent();
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // the move's transaction, held open after the guard has run (handle() joins it)
            CompletableFuture<Void> move = CompletableFuture.runAsync(() -> inTransaction(() -> {
                handler.handle(scheduled(MOVED));
                checked.countDown();
                await(release);
            }));
            assertThat(checked.await(10, SECONDS)).isTrue();

            CompletableFuture<Void> checkout = CompletableFuture.runAsync(() -> inTransaction(() -> markSold(seat)));
            assertBlocked(checkout);

            release.countDown();
            move.get(10, SECONDS);
            checkout.get(10, SECONDS);
            // serialised: the move committed first, then the seat was sold
            assertThat(saleOpensAt()).isEqualTo(MOVED);
            assertThat(jdbc.queryForObject("SELECT status FROM seat WHERE id = ?", String.class, seat))
                    .isEqualTo("SOLD");
        } finally {
            release.countDown();
        }
    }

    /** Creates the event (3 AVAILABLE seats, sale at {@link #SALE}) and returns one seat's id. */
    private long createEvent() {
        handler.handle(scheduled(SALE));
        return jdbc.queryForObject("SELECT id FROM seat WHERE event_id = ? AND number = 1", Long.class, EVENT);
    }

    /** What a checkout does to a seat: AVAILABLE to SOLD by primary key. */
    private void markSold(long seatId) {
        assertThat(jdbc.update("UPDATE seat SET status = 'SOLD', version = version + 1 WHERE id = ?", seatId))
                .isEqualTo(1);
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
    }

    private static EventScheduled scheduled(Instant saleOpensAt) {
        Instant now = Instant.parse("2026-11-14T10:00:00Z");
        return new EventScheduled(UUID.randomUUID(), EVENT, "Locking", "Test Arena", "Delhi", "Asia/Kolkata",
                now.plusSeconds(86_400 * 30L), saleOpensAt, List.of(new RowSpec("A", 3, "GOLD")), now);
    }

    private Instant saleOpensAt() {
        return jdbc.queryForObject("SELECT sale_opens_at FROM event_ref WHERE id = ?", LocalDateTime.class, EVENT)
                .toInstant(ZoneOffset.UTC);
    }

    private static void assertBlocked(Future<?> work) {
        assertThatThrownBy(() -> work.get(BLOCKED_MILLIS, MILLISECONDS)).as("waits for the other transaction's lock")
                .isInstanceOf(TimeoutException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
