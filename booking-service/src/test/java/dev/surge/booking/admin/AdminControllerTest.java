package dev.surge.booking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.booking.hold.RedisKeys;
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

    private void buy(String user, String key, String seatIdsJson) throws Exception {
        String holdId = hold(user, seatIdsJson);
        mvc.perform(post("/api/bookings/orders").header("X-User-Id", user).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"holdId\":\"" + holdId + "\"}"))
                .andExpect(status().isCreated());
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void integrityAfterSales() throws Exception {
        buy("u1", "k1", "[1,2]");
        mvc.perform(get("/api/bookings/admin/events/1/integrity")).andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(1))
                .andExpect(jsonPath("$.totalSeats").value(500))
                .andExpect(jsonPath("$.soldSeats").value(2))
                .andExpect(jsonPath("$.orderItems").value(2))
                .andExpect(jsonPath("$.distinctSeatsSold").value(2))
                .andExpect(jsonPath("$.oversold").value(0));
    }

    @Test
    void integrityUnknownEventIs404() throws Exception {
        mvc.perform(get("/api/bookings/admin/events/999/integrity")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/event-not-found"));
    }

    @Test
    void resetRestoresEverything() throws Exception {
        buy("u1", "k1", "[1]");
        hold("u2", "[2]");
        redis.opsForValue().set("idem:u1:keep", "1");
        // An unrelated event's hold member must survive.
        redis.opsForZSet().add(RedisKeys.HOLD_EXPIRY, "2:otherhold:9", 1.0);

        mvc.perform(post("/api/bookings/admin/events/1/reset").param("opensInSeconds", "60"))
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
        mvc.perform(post("/api/bookings/admin/events/1/reset")).andExpect(status().isNoContent());
        hold("u1", "[1]");
    }

    @Test
    void resetUnknownEventIs404() throws Exception {
        mvc.perform(post("/api/bookings/admin/events/999/reset")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/event-not-found"));
    }

    @Test
    void resetRejectsOutOfRangeOpensIn() throws Exception {
        mvc.perform(post("/api/bookings/admin/events/1/reset").param("opensInSeconds", "86401"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/bookings/admin/events/1/reset").param("opensInSeconds", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"));
        mvc.perform(post("/api/bookings/admin/events/1/reset").param("opensInSeconds", "86400"))
                .andExpect(status().isNoContent());
    }

    @Test
    void loadtestReportRoundTrip() throws Exception {
        mvc.perform(get("/api/bookings/admin/loadtest-reports/latest")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/report-not-found"));
        mvc.perform(put("/api/bookings/admin/loadtest-reports/latest").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sold\":500}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/bookings/admin/loadtest-reports/latest")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"sold\":500}"));
    }

    @Test
    void invalidReportJsonIs400() throws Exception {
        mvc.perform(put("/api/bookings/admin/loadtest-reports/latest").contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/malformed-request"));
        mvc.perform(put("/api/bookings/admin/loadtest-reports/latest").contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedReportIs413() throws Exception {
        String big = "{\"x\":\"" + "a".repeat(300 * 1024) + "\"}";
        mvc.perform(put("/api/bookings/admin/loadtest-reports/latest").contentType(MediaType.APPLICATION_JSON)
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
