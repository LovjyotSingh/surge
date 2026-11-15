package dev.surge.booking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.booking.hold.RedisKeys;
import dev.surge.booking.support.Admin;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AdminControllerTest {

    @Autowired MockMvc mvc;
    @Autowired TestData testData;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
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
        return mvc.perform(post("/api/bookings/orders").header("X-User-Id", user).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"holdId\":\"" + holdId + "\"}"));
    }

    private int buy(String user, String key, String seatIdsJson) throws Exception {
        MvcResult r = checkout(user, key, hold(user, seatIdsJson)).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.orderId");
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void everyAdminEndpointNeedsAnIdentityAndTheAdminRole() throws Exception {
        record Call(String method, String path) {
        }
        for (Call c : java.util.List.of(new Call("POST", "/api/bookings/admin/events/1/reset"),
                new Call("GET", "/api/bookings/admin/events/1/integrity"),
                new Call("PUT", "/api/bookings/admin/loadtest-reports/latest"),
                new Call("GET", "/api/bookings/admin/loadtest-reports/latest"))) {
            var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .request(org.springframework.http.HttpMethod.valueOf(c.method()), c.path());
            // no identity at all: 401
            mvc.perform(req).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-user"));
            // a role header without a user id is still anonymous
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .request(org.springframework.http.HttpMethod.valueOf(c.method()), c.path())
                            .header("X-User-Roles", "ADMIN"))
                    .andExpect(status().isUnauthorized());
            for (String roles : new String[] {null, "", "USER", "admin", "SUPERADMIN", "USER,ADMINISTRATOR"}) {
                var r = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(org.springframework.http.HttpMethod.valueOf(c.method()), c.path())
                        .header("X-User-Id", "u-7");
                mvc.perform(roles == null ? r : r.header("X-User-Roles", roles))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.type").value("https://surge.dev/problems/forbidden"));
            }
        }
    }

    @Test
    void resetWithoutAdminDeletesNothing() throws Exception {
        buy("u1", "k1", "[1]");
        mvc.perform(post("/api/bookings/admin/events/1/reset").header("X-User-Id", "u1").header("X-User-Roles", "USER"))
                .andExpect(status().isForbidden());
        assertThat(count("SELECT COUNT(*) FROM orders WHERE event_id = 1")).isEqualTo(1);
        mvc.perform(post("/api/bookings/admin/events/1/reset").header("X-User-Id", "u-admin")
                        .header("X-User-Roles", "USER, ADMIN"))
                .andExpect(status().isNoContent());
    }

    @Test
    void integrityAfterSales() throws Exception {
        buy("u1", "k1", "[1,2]");
        mvc.perform(Admin.as(get("/api/bookings/admin/events/1/integrity"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(1))
                .andExpect(jsonPath("$.totalSeats").value(500))
                .andExpect(jsonPath("$.soldSeats").value(2))
                .andExpect(jsonPath("$.orderItems").value(2))
                .andExpect(jsonPath("$.distinctSeatsSold").value(2))
                .andExpect(jsonPath("$.oversold").value(0))
                .andExpect(jsonPath("$.consistent").value(true));
    }

    @Test
    void integrityFlagsASoldSeatWithoutAnOrder() throws Exception {
        buy("u1", "k1", "[1]");
        jdbc.update("UPDATE seat SET status = 'SOLD' WHERE id = 10");
        mvc.perform(Admin.as(get("/api/bookings/admin/events/1/integrity"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.soldSeats").value(2))
                .andExpect(jsonPath("$.distinctSeatsSold").value(1))
                .andExpect(jsonPath("$.oversold").value(0))
                .andExpect(jsonPath("$.consistent").value(false));
    }

    /** C1: a load test re-run reuses its keys after a reset. The stale key must not 404 or strand the new hold. */
    @Test
    void sameIdempotencyKeyBuysAgainAfterReset() throws Exception {
        int firstOrder = buy("u1", "K", "[1]");

        mvc.perform(Admin.as(post("/api/bookings/admin/events/1/reset"))).andExpect(status().isNoContent());
        String key = RedisKeys.idempotency("u1", "K");
        assertThat(redis.opsForValue().get(key)).isEqualTo(String.valueOf(firstOrder)); // reset keeps idem keys

        String holdId = hold("u1", "[1]");
        MvcResult r = checkout("u1", "K", holdId).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")).andReturn();
        int secondOrder = JsonPath.read(r.getResponse().getContentAsString(), "$.orderId");

        assertThat(secondOrder).isNotEqualTo(firstOrder);
        assertThat(redis.opsForValue().get(key)).isEqualTo(String.valueOf(secondOrder));
        assertThat(count("SELECT COUNT(*) FROM orders WHERE event_id = 1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM seat WHERE id = 1 AND status = 'SOLD'")).isEqualTo(1);
        // No stuck hold: the hold was finalized like any other sale.
        assertThat(redis.hasKey(RedisKeys.hold(holdId))).isFalse();
        assertThat(redis.hasKey(RedisKeys.userHold(1, "u1"))).isFalse();
        assertThat(redis.hasKey(RedisKeys.seat(1, 1))).isFalse();
        assertThat(redis.opsForSet().isMember(RedisKeys.sold(1), "1")).isTrue();
        assertThat(redis.opsForZSet().range(RedisKeys.HOLD_EXPIRY, 0, -1)).isEmpty();
    }

    @Test
    void integrityUnknownEventIs404() throws Exception {
        mvc.perform(Admin.as(get("/api/bookings/admin/events/999/integrity"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/event-not-found"));
    }

    @Test
    void resetRestoresEverything() throws Exception {
        buy("u1", "k1", "[1]");
        hold("u2", "[2]");
        redis.opsForValue().set("idem:u1:keep", "1");
        // An unrelated event's hold member must survive.
        redis.opsForZSet().add(RedisKeys.HOLD_EXPIRY, "2:otherhold:9", 1.0);

        mvc.perform(Admin.as(post("/api/bookings/admin/events/1/reset")).param("opensInSeconds", "60"))
                .andExpect(status().isNoContent());

        assertThat(count("SELECT COUNT(*) FROM seat WHERE event_id = 1 AND status <> 'AVAILABLE'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM orders WHERE event_id = 1")).isZero();
        assertThat(count("SELECT COUNT(*) FROM order_item")).isZero();
        Set<String> keys = redis.keys("*");
        assertThat(keys).noneMatch(k -> k.startsWith("sold:1") || k.startsWith("seat:1:")
                || k.startsWith("userhold:1:") || k.startsWith("hold:"));
        assertThat(redis.opsForZSet().range(RedisKeys.HOLD_EXPIRY, 0, -1)).containsExactly("2:otherhold:9");
        assertThat(redis.hasKey("idem:u1:keep")).isTrue();

        MvcResult r = mvc.perform(get("/api/bookings/events/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[?(@.state != 'AVAILABLE')]").isEmpty()).andReturn();
        Instant opens = Instant.parse(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.saleOpensAt"));
        assertThat(Duration.between(Instant.now(), opens).minusSeconds(60).abs()).isLessThan(Duration.ofSeconds(10));

        mvc.perform(post("/api/bookings/holds").header("X-User-Id", "u3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":1,\"seatIds\":[1]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/sale-not-open"));
    }

    @Test
    void resetDefaultsToOpenNow() throws Exception {
        testData.setSaleOpensAt(1L, Instant.now().plusSeconds(3600));
        mvc.perform(Admin.as(post("/api/bookings/admin/events/1/reset"))).andExpect(status().isNoContent());
        hold("u1", "[1]");
    }

    @Test
    void resetUnknownEventIs404() throws Exception {
        mvc.perform(Admin.as(post("/api/bookings/admin/events/999/reset"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/event-not-found"));
    }

    @Test
    void resetRejectsOutOfRangeOpensIn() throws Exception {
        mvc.perform(Admin.as(post("/api/bookings/admin/events/1/reset")).param("opensInSeconds", "86401"))
                .andExpect(status().isBadRequest());
        mvc.perform(Admin.as(post("/api/bookings/admin/events/1/reset")).param("opensInSeconds", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"));
        mvc.perform(Admin.as(post("/api/bookings/admin/events/1/reset")).param("opensInSeconds", "86400"))
                .andExpect(status().isNoContent());
    }

    @Test
    void loadtestReportRoundTrip() throws Exception {
        mvc.perform(Admin.as(get("/api/bookings/admin/loadtest-reports/latest"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/report-not-found"));
        mvc.perform(Admin.as(put("/api/bookings/admin/loadtest-reports/latest")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sold\":500}"))
                .andExpect(status().isNoContent());
        mvc.perform(Admin.as(get("/api/bookings/admin/loadtest-reports/latest"))).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"sold\":500}"));
    }

    @Test
    void invalidReportJsonIs400() throws Exception {
        mvc.perform(Admin.as(put("/api/bookings/admin/loadtest-reports/latest")).contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/malformed-request"));
        mvc.perform(Admin.as(put("/api/bookings/admin/loadtest-reports/latest")).contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedReportIs413() throws Exception {
        String big = "{\"x\":\"" + "a".repeat(300 * 1024) + "\"}";
        mvc.perform(Admin.as(put("/api/bookings/admin/loadtest-reports/latest")).contentType(MediaType.APPLICATION_JSON)
                        .content(big))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/report-too-large"));
    }

    @Test
    void openApiIsServed() throws Exception {
        mvc.perform(get("/api/bookings/v3/api-docs")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/bookings/holds")));
    }
}
