package dev.surge.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.booking.support.Identity;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class HoldControllerTest {

    @Autowired MockMvc mvc;
    @Autowired TestData testData;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void reset() {
        testData.resetEvent(1L);
    }

    private ResultActions hold(String user, long eventId, String seatIdsJson) throws Exception {
        var req = post("/api/bookings/holds").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":" + eventId + ",\"seatIds\":" + seatIdsJson + "}");
        // no user: the anonymous token the gateway sends on public routes
        req.with(user == null ? Identity.anonymous() : Identity.user(user));
        return mvc.perform(req);
    }

    private String seatState(long seatId) throws Exception {
        MvcResult r = mvc.perform(get("/api/bookings/events/1").with(Identity.anonymous())).andExpect(status().isOk())
                .andReturn();
        List<String> states = JsonPath.read(r.getResponse().getContentAsString(),
                "$.seats[?(@.id==" + seatId + ")].state");
        return states.get(0);
    }

    @Test
    void holdsSeats() throws Exception {
        Instant before = Instant.now();
        MvcResult r = hold("u1", 1, "[1,2]").andExpect(status().isCreated())
                .andExpect(jsonPath("$.holdId").isNotEmpty())
                .andExpect(jsonPath("$.eventId").value(1))
                .andExpect(jsonPath("$.seatIds[0]").value(1))
                .andExpect(jsonPath("$.quotedTotalInr").value(9998.0))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[1].seatId").value(2))
                .andExpect(jsonPath("$.lines[1].priceInr").value(4999.0))
                .andReturn();
        String expiresAt = JsonPath.read(r.getResponse().getContentAsString(), "$.expiresAt");
        assertThat(Duration.between(before.plus(Duration.ofMinutes(5)), Instant.parse(expiresAt)).abs())
                .isLessThan(Duration.ofSeconds(5));
        assertThat(seatState(1)).isEqualTo("HELD");
        assertThat(seatState(2)).isEqualTo("HELD");
        assertThat(seatState(3)).isEqualTo("AVAILABLE");
    }

    @Test
    void holdQuotesSurgePriceWhenFewSeatsAreLeft() throws Exception {
        // 401 of 500 seats sold leaves 99 (< 20%): surge adds 20%, 4999 x 1.2 = 5998.8 -> 5999.
        String[] sold = LongStream.rangeClosed(100, 500).mapToObj(String::valueOf).toArray(String[]::new);
        redis.opsForSet().add(RedisKeys.sold(1), sold);
        MvcResult r = hold("u1", 1, "[1]").andExpect(status().isCreated())
                .andExpect(jsonPath("$.quotedTotalInr").value(5999.0))
                .andExpect(jsonPath("$.lines[0].priceInr").value(5999.0))
                .andReturn();
        String holdId = JsonPath.read(r.getResponse().getContentAsString(), "$.holdId");
        // The quote is stored with the hold; checkout charges this and does not re-price.
        assertThat(redis.opsForHash().get(RedisKeys.hold(holdId), "prices")).isEqualTo("5999.00");
    }

    @Test
    void takenSeatReturns409WithIds() throws Exception {
        hold("u1", 1, "[1,2]").andExpect(status().isCreated());
        hold("u2", 1, "[2,3]").andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/seat-taken"))
                .andExpect(jsonPath("$.seatIds.length()").value(1))
                .andExpect(jsonPath("$.seatIds[0]").value(2));
        assertThat(seatState(3)).isEqualTo("AVAILABLE");
    }

    @Test
    void secondHoldBySameUserIsRejected() throws Exception {
        MvcResult first = hold("u1", 1, "[1]").andExpect(status().isCreated()).andReturn();
        String holdId = JsonPath.read(first.getResponse().getContentAsString(), "$.holdId");
        hold("u1", 1, "[5]").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/hold-exists"))
                .andExpect(jsonPath("$.holdId").value(holdId));
        assertThat(seatState(5)).isEqualTo("AVAILABLE");
    }

    @Test
    void soldSeatCannotBeHeld() throws Exception {
        redis.opsForSet().add(RedisKeys.sold(1), "4");
        hold("u1", 1, "[4]").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/seat-taken"))
                .andExpect(jsonPath("$.seatIds[0]").value(4))
                .andExpect(jsonPath("$.seatIds.length()").value(1));
    }

    @Test
    void seatFromOtherEventIsInvalid() throws Exception {
        hold("u1", 1, "[1,501]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/invalid-seats"))
                .andExpect(jsonPath("$.seatIds[0]").value(501));
        assertThat(seatState(1)).isEqualTo("AVAILABLE");
    }

    @Test
    void duplicateSeatIdsAreInvalid() throws Exception {
        hold("u1", 1, "[1,1]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/invalid-seats"));
        assertThat(seatState(1)).isEqualTo("AVAILABLE");
    }

    @Test
    void moreThanEightSeatsFailsValidation() throws Exception {
        String ids = LongStream.rangeClosed(1, 9).boxed().map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
        hold("u1", 1, ids).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.errors.seatIds").exists());
        assertThat(seatState(1)).isEqualTo("AVAILABLE");
    }

    @Test
    void emptySeatsFailsValidation() throws Exception {
        hold("u1", 1, "[]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"));
        assertThat(seatState(1)).isEqualTo("AVAILABLE");
    }

    @Test
    void beforeSaleOpensIsRejected() throws Exception {
        testData.setSaleOpensAt(1L, Instant.now().plus(Duration.ofHours(1)));
        hold("u1", 1, "[1]").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/sale-not-open"))
                .andExpect(jsonPath("$.saleOpensAt").isNotEmpty());
        assertThat(seatState(1)).isEqualTo("AVAILABLE");
    }

    @Test
    void missingUserIs401() throws Exception {
        hold(null, 1, "[1]").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-user"));
        // a forged legacy header next to the anonymous token is still no user
        mvc.perform(post("/api/bookings/holds").with(Identity.anonymous()).header("X-User-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatIds\":[1]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-user"));
    }

    @Test
    void unknownEventIs404() throws Exception {
        hold("u1", 999, "[1]").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/event-not-found"));
    }

    @Test
    void emptyBucketReturns429WithRetryAfter() throws Exception {
        Long nowMillis = redis.execute((RedisCallback<Long>) c -> c.serverCommands().time());
        String key = RedisKeys.holdRate("u9");
        redis.opsForHash().putAll(key, Map.of("tokens", "0", "ts", String.valueOf(nowMillis)));
        redis.expire(key, Duration.ofSeconds(5));
        MvcResult r = hold("u9", 1, "[1]").andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/rate-limited"))
                .andReturn();
        assertThat(Long.parseLong(r.getResponse().getHeader("Retry-After"))).isGreaterThanOrEqualTo(1);
    }

    /** S5: a hold needs the gateway's word that the user's email is verified; it works once the token says so. */
    @Test
    void anUnverifiedUsersHoldIs403EmailUnverifiedAndWorksAfterVerifying() throws Exception {
        mvc.perform(post("/api/bookings/holds").with(Identity.unverifiedUser("u-ev"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatIds\":[5]}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/email-unverified"))
                .andExpect(jsonPath("$.requestId").isString());
        // before the body is read: an invalid body from an unverified user is still 403
        mvc.perform(post("/api/bookings/holds").with(Identity.unverifiedUser("u-ev"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(seatState(5)).isEqualTo("AVAILABLE");

        // the token after POST /api/auth/verify carries ev: true
        hold("u-ev", 1, "[5]").andExpect(status().isCreated());
        assertThat(seatState(5)).isEqualTo("HELD");
    }

    @Test
    void aUserHoldsSeatsInAtMostThreeEventsAtOnce(@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc)
            throws Exception {
        long extraEvent = 9101;
        jdbc.update("INSERT INTO event_ref (id, title, venue_name, sale_opens_at, zone_id, status) VALUES "
                + "(?, 'Cap test', 'Nowhere', '2026-01-01 00:00:00', 'UTC', 'ACTIVE')", extraEvent);
        jdbc.update("INSERT INTO seat (id, event_id, section, row_label, number, tier, status, version) VALUES "
                + "(910101, ?, 'MAIN', 'A', 1, 'GOLD', 'AVAILABLE', 0)", extraEvent);
        try {
            long seat2 = jdbc.queryForObject("SELECT MIN(id) FROM seat WHERE event_id = 2", Long.class);
            long seat3 = jdbc.queryForObject("SELECT MIN(id) FROM seat WHERE event_id = 3", Long.class);
            String first = JsonPath.read(hold("u-cap", 1, "[7]").andExpect(status().isCreated()).andReturn()
                    .getResponse().getContentAsString(), "$.holdId");
            hold("u-cap", 2, "[" + seat2 + "]").andExpect(status().isCreated());
            hold("u-cap", 3, "[" + seat3 + "]").andExpect(status().isCreated());

            hold("u-cap", extraEvent, "[910101]").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/too-many-holds"))
                    .andExpect(jsonPath("$.maxActiveHolds").value(3));
            // nothing was held, and another user is not affected
            hold("u-other", extraEvent, "[910101]").andExpect(status().isCreated());
            assertThat(redis.opsForZSet().size(RedisKeys.userHolds("u-cap"))).isEqualTo(3);

            // buying one of them frees a slot at once
            mvc.perform(post("/api/bookings/orders").with(Identity.user("u-cap")).header("Idempotency-Key",
                            "cap-" + first).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"holdId\":\"" + first + "\"}"))
                    .andExpect(status().is2xxSuccessful());
            assertThat(redis.opsForZSet().size(RedisKeys.userHolds("u-cap"))).isEqualTo(2);
            hold("u-cap", 1, "[8]").andExpect(status().isCreated());
        } finally {
            testData.resetEvent(1L);
            testData.deleteEvent(extraEvent);
        }
    }

    @Test
    void missingUserWinsOverBodyValidation() throws Exception {
        hold(null, 1, "[]").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-user"));
    }

    @Test
    void malformedBodyIs400() throws Exception {
        hold("u1", 1, "\"x\"").andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/malformed-request"))
                .andExpect(jsonPath("$.title").value("Malformed request body"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void nonJsonContentTypeIs415() throws Exception {
        mvc.perform(post("/api/bookings/holds").with(Identity.user("u1"))
                        .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/unsupported-media-type"))
                .andExpect(jsonPath("$.requestId").isString());
    }
}
