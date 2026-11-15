package dev.surge.booking.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.booking.support.Identity;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class SeatStreamTest {

    @Autowired MockMvc mvc;
    @Autowired SeatStreamRegistry registry;
    @Autowired TestData testData;

    @BeforeEach
    void setUp() {
        testData.resetEvent(1L);
    }

    @Test
    void streamReceivesHoldEvents() throws Exception {
        MvcResult r = mvc.perform(get("/api/bookings/events/1/stream").with(Identity.anonymous()))
                .andExpect(request().asyncStarted()).andReturn();
        try {
            mvc.perform(post("/api/bookings/holds").with(Identity.user("stream-u1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventId\":1,\"seatIds\":[4]}"))
                    .andExpect(status().is2xxSuccessful());

            String body = "";
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                body = r.getResponse().getContentAsString();
                if (body.contains("event:seats") && body.contains("\"seatIds\":[4]") && body.contains("HELD")) {
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(body).contains("event:seats").contains("\"seatIds\":[4]").contains("HELD");
        } finally {
            registry.completeAll(1L);
        }
    }

    @Test
    void unknownEventIs404() throws Exception {
        mvc.perform(get("/api/bookings/events/9999/stream").with(Identity.anonymous()))
                .andExpect(status().isNotFound());
    }
}
