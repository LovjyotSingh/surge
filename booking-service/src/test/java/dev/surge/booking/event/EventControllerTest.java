package dev.surge.booking.event;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class EventControllerTest {

    @Autowired MockMvc mvc;
    @Autowired TestData testData;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void reset() {
        testData.resetEvent(1L);
    }

    @Test
    void listsEvents() throws Exception {
        mvc.perform(get("/api/bookings/events")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].totalSeats").value(500))
                .andExpect(jsonPath("$[0].soldSeats").value(0));
    }

    @Test
    void seatMapShowsHeldAndSold() throws Exception {
        // Seed ids are not guaranteed to follow row/number order, so derive the first three seats by order.
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM seat WHERE event_id = 1 ORDER BY row_label, number LIMIT 3", Long.class);
        jdbc.update("UPDATE seat SET status = 'SOLD' WHERE id = ?", ids.get(0));
        redis.opsForValue().set("seat:1:" + ids.get(1), "h1");
        mvc.perform(get("/api/bookings/events/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[0].state").value("SOLD"))
                .andExpect(jsonPath("$.seats[1].state").value("HELD"))
                .andExpect(jsonPath("$.seats[2].state").value("AVAILABLE"))
                .andExpect(jsonPath("$.seats[0].basePriceInr").value(4999));
    }

    @Test
    void unknownEventIs404Problem() throws Exception {
        mvc.perform(get("/api/bookings/events/999")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/event-not-found"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}
