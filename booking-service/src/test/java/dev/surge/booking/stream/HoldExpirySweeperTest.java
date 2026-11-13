package dev.surge.booking.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.surge.booking.event.SeatState;
import dev.surge.booking.hold.HoldResponse;
import dev.surge.booking.hold.HoldService;
import dev.surge.booking.hold.RedisKeys;
import dev.surge.booking.order.CheckoutService;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@IntegrationTest
@SuppressWarnings("rawtypes")
class HoldExpirySweeperTest {

    @Autowired StringRedisTemplate redis;
    @Autowired RedisScript<List> sweepScript;
    @Autowired Clock clock;
    @Autowired MeterRegistry meters;
    @Autowired HoldService holds;
    @Autowired CheckoutService checkout;
    @Autowired TestData testData;

    SeatEventPublisher publisher;
    HoldExpirySweeper sweeper;

    @BeforeEach
    void setUp() {
        testData.resetEvent(1L);
        publisher = mock(SeatEventPublisher.class);
        sweeper = new HoldExpirySweeper(redis, sweepScript, publisher, clock, meters);
    }

    /** Simulates TTL expiry without waiting: expires the keys and back-dates the expiry-set score. */
    private void expire(HoldResponse hold, String userId) throws InterruptedException {
        String member = (String) redis.opsForHash().get(RedisKeys.hold(hold.holdId()), "member");
        redis.expire(RedisKeys.hold(hold.holdId()), Duration.ofMillis(1));
        for (Long s : hold.seatIds()) {
            redis.expire(RedisKeys.seat(hold.eventId(), s), Duration.ofMillis(1));
        }
        redis.expire(RedisKeys.userHold(hold.eventId(), userId), Duration.ofMillis(1));
        redis.opsForZSet().add(RedisKeys.HOLD_EXPIRY, member, clock.millis() - 1000);
        Thread.sleep(50);
    }

    @Test
    void sweeperFreesExpiredSeats() throws Exception {
        HoldResponse h = holds.hold("u1", 1L, List.of(1L, 2L));
        expire(h, "u1");
        double before = meters.counter("holds_expired_total").count();

        assertThat(sweeper.sweep()).isEqualTo(1);

        verify(publisher).publish(1L, List.of(1L, 2L), SeatState.AVAILABLE);
        assertThat(redis.opsForZSet().zCard(RedisKeys.HOLD_EXPIRY)).isZero();
        assertThat(meters.counter("holds_expired_total").count()).isEqualTo(before + 1);
        assertThat(holds.hold("u1", 1L, List.of(1L, 2L)).seatIds()).containsExactly(1L, 2L);
    }

    @Test
    void sweeperSkipsSeatsReheldByOthers() throws Exception {
        HoldResponse h = holds.hold("u1", 1L, List.of(3L));
        expire(h, "u1");
        holds.hold("u2", 1L, List.of(3L));

        sweeper.sweep();

        verify(publisher, never()).publish(anyLong(), anyList(), eq(SeatState.AVAILABLE));
        assertThat(redis.hasKey(RedisKeys.seat(1L, 3L))).isTrue();
    }

    @Test
    void sweeperIgnoresCheckedOutHolds() {
        HoldResponse h = holds.hold("u1", 1L, List.of(5L));
        checkout.checkout("u1", "key-sweep-1", h.holdId());

        assertThat(sweeper.sweep()).isZero();

        verify(publisher, never()).publish(anyLong(), anyList(), eq(SeatState.AVAILABLE));
    }
}
