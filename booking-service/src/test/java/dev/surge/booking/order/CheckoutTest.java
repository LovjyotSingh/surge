package dev.surge.booking.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.booking.hold.RedisKeys;
import dev.surge.booking.payment.PaymentGateway;
import dev.surge.booking.payment.PaymentRequest;
import dev.surge.booking.payment.PaymentResult;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class CheckoutTest {

    @Autowired MockMvc mvc;
    @Autowired TestData testData;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    /** The type-level spy from @IntegrationTest, delegating to FakePaymentGateway (failure-rate 0.0). */
    @Autowired PaymentGateway gateway;
    @Autowired IdempotencyStore idempotency;

    @BeforeEach
    void reset() {
        testData.resetEvent(1L);
        jdbc.update("DELETE FROM outbox");
    }

    private String hold(String user, String seatIdsJson) throws Exception {
        MvcResult r = mvc.perform(post("/api/bookings/holds").header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":1,\"seatIds\":" + seatIdsJson + "}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.holdId");
    }

    private ResultActions checkout(String user, String key, String holdId) throws Exception {
        var req = post("/api/bookings/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"holdId\":\"" + holdId + "\"}");
        if (user != null) {
            req.header("X-User-Id", user);
        }
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private List<String> seatStates(String filter) throws Exception {
        MvcResult r = mvc.perform(get("/api/bookings/events/1")).andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.seats[?(" + filter + ")].state");
    }

    @Test
    void checkoutSellsSeatsAndWritesOutbox() throws Exception {
        String holdId = hold("u1", "[1,2]");

        checkout("u1", "k1", holdId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalInr").value(9998.0))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(count("SELECT COUNT(*) FROM seat WHERE id IN (1,2) AND status = 'SOLD'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE event_id = 1")).isEqualTo(1);
        List<Map<String, Object>> outbox = jdbc.queryForList(
                "SELECT type, destination, published_at FROM outbox ORDER BY id");
        assertThat(outbox).hasSize(2);
        assertThat(outbox).extracting(m -> m.get("type") + "|" + m.get("destination"))
                .containsExactlyInAnyOrder("OrderConfirmed|kafka:orders.v1", "TicketRequested|rabbit:tickets");
        assertThat(outbox).allMatch(m -> m.get("published_at") == null);

        assertThat(redis.opsForSet().members(RedisKeys.sold(1))).containsExactlyInAnyOrder("1", "2");
        assertThat(redis.hasKey(RedisKeys.hold(holdId))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userHold(1, "u1"))).isFalse();
        assertThat(redis.hasKey(RedisKeys.seat(1, 1))).isFalse();
        assertThat(redis.opsForZSet().range(RedisKeys.HOLD_EXPIRY, 0, -1)).isEmpty();

        assertThat(seatStates("@.id==1 || @.id==2")).containsExactly("SOLD", "SOLD");
    }

    @Test
    void replayReturnsSameOrder() throws Exception {
        String holdId = hold("u1", "[1,2]");
        MvcResult first = checkout("u1", "k1", holdId).andExpect(status().isCreated()).andReturn();
        String firstBody = first.getResponse().getContentAsString();
        int orderId = JsonPath.read(firstBody, "$.orderId");

        MvcResult replay = checkout("u1", "k1", holdId).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.totalInr").value(9998.0)).andReturn();
        String replayBody = replay.getResponse().getContentAsString();

        assertThat(replayBody).isEqualTo(firstBody);
        assertThat((String) JsonPath.read(replayBody, "$.createdAt")).isEqualTo(JsonPath.read(firstBody, "$.createdAt"));
        assertThat(count("SELECT COUNT(*) FROM orders WHERE event_id = 1")).isEqualTo(1);
        verify(gateway, times(1)).charge(any());
    }

    @Test
    void redisFlushedKeyStillReplaysFromDatabase() throws Exception {
        String h1 = hold("u1", "[1,2]");
        MvcResult first = checkout("u1", "k1", h1).andExpect(status().isCreated()).andReturn();
        String firstBody = first.getResponse().getContentAsString();

        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        String h2 = hold("u1", "[3,4]");

        MvcResult retry = checkout("u1", "k1", h2).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true")).andReturn();

        assertThat(retry.getResponse().getContentAsString()).isEqualTo(firstBody);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE event_id = 1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM seat WHERE id IN (3,4) AND status = 'SOLD'")).isZero();
        // Design: the DB lookup happens before charging, so the retry is never charged (or refunded).
        verify(gateway, times(1)).charge(any());
        verify(gateway, never()).refund(any());
        // The key is re-completed so later retries are served from Redis again.
        int orderId = JsonPath.read(firstBody, "$.orderId");
        assertThat(redis.opsForValue().get(RedisKeys.idempotency("u1", "k1"))).isEqualTo(String.valueOf(orderId));
    }

    @Test
    void concurrentDuplicateIsRejected() throws Exception {
        String holdId = hold("u1", "[1]");
        redis.opsForValue().set(RedisKeys.idempotency("u1", "k"), "IN_PROGRESS");

        checkout("u1", "k", holdId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/request-in-progress"));
        verify(gateway, never()).charge(any());
        assertThat(redis.opsForValue().get(RedisKeys.idempotency("u1", "k"))).isEqualTo("IN_PROGRESS");
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        String holdId = hold("u1", "[1]");
        checkout("u1", null, holdId).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-idempotency-key"));
        checkout("u1", " ", holdId).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-idempotency-key"));
        checkout("u1", "x".repeat(65), holdId).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-idempotency-key"));
    }

    @Test
    void otherUsersHoldIsNotFound() throws Exception {
        String holdId = hold("u1", "[1]");
        checkout("u2", "k", holdId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/hold-not-found"));
        verify(gateway, never()).charge(any());
    }

    @Test
    void declinedPaymentKeepsHold() throws Exception {
        String holdId = hold("u1", "[1,2]");
        doReturn(new PaymentResult(false, null, "card_declined")).when(gateway).charge(any());

        checkout("u1", "k1", holdId).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/payment-failed"))
                .andExpect(jsonPath("$.reason").value("card_declined"));

        assertThat(redis.hasKey(RedisKeys.hold(holdId))).isTrue();
        assertThat(redis.hasKey(RedisKeys.seat(1, 1))).isTrue();
        assertThat(redis.hasKey(RedisKeys.idempotency("u1", "k1"))).isFalse();
        assertThat(count("SELECT COUNT(*) FROM seat WHERE id IN (1,2) AND status = 'SOLD'")).isZero();
        assertThat(seatStates("@.id==1 || @.id==2")).containsExactly("HELD", "HELD");

        Mockito.reset(gateway);
        checkout("u1", "k2", holdId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalInr").value(9998.0));
    }

    @Test
    void expiredHoldCannotCheckout() throws Exception {
        String holdId = hold("u1", "[1,2]");
        Duration oneMs = Duration.ofMillis(1);
        redis.expire(RedisKeys.hold(holdId), oneMs);
        redis.expire(RedisKeys.seat(1, 1), oneMs);
        redis.expire(RedisKeys.seat(1, 2), oneMs);
        redis.expire(RedisKeys.userHold(1, "u1"), oneMs);
        Thread.sleep(50);

        checkout("u1", "k1", holdId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/hold-not-found"));
        verify(gateway, never()).charge(any());
    }

    @Test
    void redisFlushCannotCauseDoubleSale() throws Exception {
        String h1 = hold("u1", "[7]");
        checkout("u1", "k1", h1).andExpect(status().isCreated());

        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });

        String h2 = hold("u2", "[7]");
        checkout("u2", "k2", h2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/seat-taken"));

        assertThat(count("SELECT COUNT(*) FROM order_item WHERE seat_id = 7")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE user_id = 'u2'")).isZero();
        verify(gateway, times(1)).refund(any());
        assertThat(redis.hasKey(RedisKeys.idempotency("u2", "k2"))).isFalse();

        // The lost hold is released (not left to expire), and nothing is added to the sold set.
        assertThat(redis.hasKey(RedisKeys.hold(h2))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userHold(1, "u2"))).isFalse();
        assertThat(redis.hasKey(RedisKeys.seat(1, 7))).isFalse();
        assertThat(redis.opsForZSet().range(RedisKeys.HOLD_EXPIRY, 0, -1)).isEmpty();
        assertThat(redis.opsForSet().isMember(RedisKeys.sold(1), "7")).isFalse();
        hold("u2", "[8]"); // no hold-exists: u2 can pick again straight away
    }

    @Test
    void priceIsLockedAtHoldTimeAndNotRepriced() throws Exception {
        // Sale opened a minute ago: the early-bird rule takes 10% off, 4999 -> 4499 per PLATINUM seat.
        testData.setSaleOpensAt(1L, Instant.now().minusSeconds(60));
        MvcResult held = mvc.perform(post("/api/bookings/holds").header("X-User-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatIds\":[1,2]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quotedTotalInr").value(8998.0))
                .andExpect(jsonPath("$.lines[0].seatId").value(1))
                .andExpect(jsonPath("$.lines[0].priceInr").value(4499.0))
                .andReturn();
        String holdId = JsonPath.read(held.getResponse().getContentAsString(), "$.holdId");

        // The early-bird window is over by checkout time; re-pricing now would give 9998.
        testData.setSaleOpensAt(1L, Instant.parse("2026-01-01T00:00:00Z"));
        checkout("u1", "k1", holdId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalInr").value(8998.0))
                .andExpect(jsonPath("$.lines[0].priceInr").value(4499.0))
                .andExpect(jsonPath("$.lines[1].priceInr").value(4499.0));

        ArgumentCaptor<PaymentRequest> charged = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(gateway).charge(charged.capture());
        assertThat(charged.getValue().amountInr()).isEqualByComparingTo(new BigDecimal("8998"));
        assertThat(count("SELECT COUNT(*) FROM order_item WHERE price_inr = 4499.00")).isEqualTo(2);
    }

    @Test
    void inProgressMarkerIsShortLivedAndOrderIdLasts24Hours() throws Exception {
        String holdId = hold("u1", "[1]");
        String key = RedisKeys.idempotency("u1", "k1");
        AtomicLong ttlWhileRunning = new AtomicLong();
        doAnswer(inv -> {
            assertThat(redis.opsForValue().get(key)).isEqualTo("IN_PROGRESS");
            ttlWhileRunning.set(redis.getExpire(key, TimeUnit.SECONDS));
            return inv.callRealMethod();
        }).when(gateway).charge(any());

        checkout("u1", "k1", holdId).andExpect(status().isCreated());

        assertThat(ttlWhileRunning.get()).isBetween(1L, 120L);
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isGreaterThan(Duration.ofHours(23).toSeconds());
    }

    @Test
    void takeOverIsCompareAndSet() {
        String key = RedisKeys.idempotency("u1", "k1");
        redis.opsForValue().set(key, "41", Duration.ofHours(24));

        assertThat(idempotency.takeOver("u1", "k1", 40)).isFalse();
        assertThat(redis.opsForValue().get(key)).isEqualTo("41");

        assertThat(idempotency.takeOver("u1", "k1", 41)).isTrue();
        assertThat(redis.opsForValue().get(key)).isEqualTo("IN_PROGRESS");
        assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 120L);
    }

    @Test
    void staleOrderIdInKeyIsTreatedAsNewRequest() throws Exception {
        // A key whose order was deleted (as an admin reset does): the stored id points at no order.
        redis.opsForValue().set(RedisKeys.idempotency("u1", "k1"), "999999", Duration.ofHours(24));
        String holdId = hold("u1", "[1]");

        MvcResult r = checkout("u1", "k1", holdId).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")).andReturn();

        int orderId = JsonPath.read(r.getResponse().getContentAsString(), "$.orderId");
        assertThat(redis.opsForValue().get(RedisKeys.idempotency("u1", "k1"))).isEqualTo(String.valueOf(orderId));
        assertThat(redis.hasKey(RedisKeys.hold(holdId))).isFalse();
    }

    @Test
    void getOrderOnlyForOwner() throws Exception {
        String holdId = hold("u1", "[1,2]");
        MvcResult r = checkout("u1", "k1", holdId).andExpect(status().isCreated()).andReturn();
        int orderId = JsonPath.read(r.getResponse().getContentAsString(), "$.orderId");

        mvc.perform(get("/api/bookings/orders/" + orderId).header("X-User-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.lines[0].rowLabel").value("A"))
                .andExpect(jsonPath("$.lines[0].number").value(1));
        mvc.perform(get("/api/bookings/orders/" + orderId).header("X-User-Id", "u2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/order-not-found"));
    }
}
