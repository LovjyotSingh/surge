package dev.surge.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
        if (user != null) {
            req.header("X-User-Id", user);
        }
        return mvc.perform(req);
    }

    private String seatState(long seatId) throws Exception {
        MvcResult r = mvc.perform(get("/api/bookings/events/1")).andExpect(status().isOk()).andReturn();
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
                .andReturn();
        String expiresAt = JsonPath.read(r.getResponse().getContentAsString(), "$.expiresAt");
        assertThat(Duration.between(before.plus(Duration.ofMinutes(5)), Instant.parse(expiresAt)).abs())
                .isLessThan(Duration.ofSeconds(5));
        assertThat(seatState(1)).isEqualTo("HELD");
        assertThat(seatState(2)).isEqualTo("HELD");
        assertThat(seatState(3)).isEqualTo("AVAILABLE");
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
    }

    @Test
    void soldSeatCannotBeHeld() throws Exception {
        redis.opsForSet().add("sold:1", "4");
        hold("u1", 1, "[4]").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/seat-taken"));
    }

    @Test
    void seatFromOtherEventIsInvalid() throws Exception {
        hold("u1", 1, "[1,501]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/invalid-seats"))
                .andExpect(jsonPath("$.seatIds[0]").value(501));
    }

    @Test
    void duplicateSeatIdsAreInvalid() throws Exception {
        hold("u1", 1, "[1,1]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/invalid-seats"));
    }

    @Test
    void moreThanEightSeatsFailsValidation() throws Exception {
        String ids = LongStream.rangeClosed(1, 9).boxed().map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
        hold("u1", 1, ids).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.errors.seatIds").exists());
    }

    @Test
    void emptySeatsFailsValidation() throws Exception {
        hold("u1", 1, "[]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"));
    }

    @Test
    void beforeSaleOpensIsRejected() throws Exception {
        testData.setSaleOpensAt(1L, Instant.now().plus(Duration.ofHours(1)));
        hold("u1", 1, "[1]").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/sale-not-open"))
                .andExpect(jsonPath("$.saleOpensAt").isNotEmpty());
    }

    @Test
    void missingUserIs401() throws Exception {
        hold(null, 1, "[1]").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-user"));
        hold("bad user!", 1, "[1]").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-user"));
    }

    @Test
    void unknownEventIs404() throws Exception {
        hold("u1", 999, "[1]").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/event-not-found"));
    }
}
