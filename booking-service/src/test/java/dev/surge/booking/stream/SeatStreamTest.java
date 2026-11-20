package dev.surge.booking.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.booking.support.Identity;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class SeatStreamTest {

    @Autowired MockMvc mvc;
    @Autowired SeatStreamRegistry registry;
    @Autowired TestData testData;
    @Value("${surge.stream.max-subscribers-per-event}") int maxPerEvent;
    @Value("${surge.stream.max-subscribers-total}") int maxTotal;

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

    /**
     * The whole stream through the identity chain: started with the gateway's anonymous token under the
     * {@code fromGateway()} event rule, then the async dispatch that writes it out is authorized again (Spring Security
     * filters ASYNC dispatches) and must still see that identity.
     */
    @Test
    void anAnonymousStreamCompletesThroughItsAsyncDispatch() throws Exception {
        MvcResult started = mvc.perform(get("/api/bookings/events/1/stream").with(Identity.anonymous()))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(post("/api/bookings/holds").with(Identity.user("stream-u2")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":1,\"seatIds\":[5]}")).andExpect(status().is2xxSuccessful());
        // The hold reaches the stream through Redis pub/sub, i.e. asynchronously: completing the stream before that
        // event is written would end it with only the ":connected" comment (seen under CPU load).
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline
                && !started.getResponse().getContentAsString().contains("\"seatIds\":[5]")) {
            Thread.sleep(50);
        }
        registry.completeAll(1L);

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"seatIds\":[5]")));
    }

    /** SEC-09: past the per-event cap a new stream is a 503 problem the client can tell apart; closing frees room. */
    @Test
    void anEventOverItsStreamCapIs503StreamCapacity() throws Exception {
        try {
            for (int i = 0; i < maxPerEvent; i++) {
                registry.subscribe(1L);
            }
            assertCapacityRefusal(1L);
        } finally {
            registry.completeAll(1L);
        }
        mvc.perform(get("/api/bookings/events/1/stream").with(Identity.anonymous()))
                .andExpect(request().asyncStarted());
        registry.completeAll(1L);
    }

    @Test
    void streamsOverTheTotalCapAre503StreamCapacity() throws Exception {
        int perEvent = Math.min(maxPerEvent, maxTotal);
        long event = 90_000L;
        try {
            for (int opened = 0; opened < maxTotal; opened++) {
                if (opened > 0 && opened % perEvent == 0) {
                    event++;
                }
                registry.subscribe(event);
            }
            // event 1 has no stream at all, but the service as a whole is full
            assertCapacityRefusal(1L);
        } finally {
            for (long e = 90_000L; e <= event; e++) {
                registry.completeAll(e);
            }
        }
    }

    private void assertCapacityRefusal(long eventId) throws Exception {
        mvc.perform(get("/api/bookings/events/" + eventId + "/stream").with(Identity.anonymous())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/stream-capacity"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void unknownEventIs404() throws Exception {
        mvc.perform(get("/api/bookings/events/9999/stream").with(Identity.anonymous()))
                .andExpect(status().isNotFound());
    }
}
