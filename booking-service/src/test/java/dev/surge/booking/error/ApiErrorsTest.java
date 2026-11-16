package dev.surge.booking.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.booking.hold.RedisKeys;
import dev.surge.booking.payment.PaymentGateway;
import dev.surge.booking.support.Admin;
import dev.surge.booking.support.Identity;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Errors raised by Spring MVC itself (and unexpected ones) use the same problem format as Surge's own errors. */
@IntegrationTest
class ApiErrorsTest {

    private static final String REQUEST_ID = "req-test-1";

    @Autowired MockMvc mvc;
    @Autowired TestData testData;
    @Autowired StringRedisTemplate redis;
    /** The type-level spy from @IntegrationTest. */
    @Autowired PaymentGateway gateway;

    @BeforeEach
    void reset() {
        testData.resetEvent(1L);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, HttpStatus expected, String slug)
            throws Exception {
        return mvc.perform(request.header("X-Request-Id", REQUEST_ID))
                .andExpect(status().is(expected.value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/" + slug))
                .andExpect(jsonPath("$.status").value(expected.value()))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void nonNumericPathVariableIs400() throws Exception {
        perform(get("/api/bookings/events/abc").with(Identity.anonymous()), HttpStatus.BAD_REQUEST, "validation-failed")
                .andExpect(jsonPath("$.detail").value("Invalid value for 'eventId'"));
    }

    @Test
    void nonNumericQueryParameterIs400() throws Exception {
        perform(Admin.as(post("/api/bookings/admin/events/1/reset")).param("opensInSeconds", "abc"),
                HttpStatus.BAD_REQUEST, "validation-failed")
                .andExpect(jsonPath("$.detail").value("Invalid value for 'opensInSeconds'"));
    }

    @Test
    void unknownRouteIs404() throws Exception {
        // a route the chain lets through (GET under /events) but no controller serves
        perform(get("/api/bookings/events/1/nope").with(Identity.anonymous()), HttpStatus.NOT_FOUND, "not-found");
    }

    @Test
    void wrongMethodIs405() throws Exception {
        // the holds rule admits any method for a user; MVC serves only POST there
        perform(put("/api/bookings/holds").with(Identity.user("u1")), HttpStatus.METHOD_NOT_ALLOWED,
                "method-not-allowed")
                .andExpect(header().string("Allow", Matchers.containsString("POST")));
    }

    @Test
    void unexpectedErrorIs500WithoutLeakingTheMessage() throws Exception {
        MvcResult held = mvc.perform(post("/api/bookings/holds").with(Identity.user("u1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatIds\":[1]}"))
                .andExpect(status().isCreated()).andReturn();
        String holdId = JsonPath.read(held.getResponse().getContentAsString(), "$.holdId");
        doThrow(new IllegalStateException("gateway password=hunter2")).when(gateway).charge(any());

        MvcResult r = perform(post("/api/bookings/orders").with(Identity.user("u1")).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"holdId\":\"" + holdId + "\"}"),
                HttpStatus.INTERNAL_SERVER_ERROR, "internal-error").andReturn();

        assertThat(r.getResponse().getContentAsString()).doesNotContain("hunter2").doesNotContain("IllegalState");
        // The failed request released its idempotency key, so the user can retry with it.
        assertThat(redis.hasKey(RedisKeys.idempotency("u1", "k1"))).isFalse();
    }

    @Test
    void customSlugsAreKept() throws Exception {
        perform(get("/api/bookings/events/999").with(Identity.anonymous()), HttpStatus.NOT_FOUND, "event-not-found");
    }

    @Test
    void slugFollowsTheStatus() {
        assertThat(ApiExceptionHandler.slugFor(HttpStatus.NOT_ACCEPTABLE)).isEqualTo("not-acceptable");
        assertThat(ApiExceptionHandler.slugFor(HttpStatus.UNSUPPORTED_MEDIA_TYPE)).isEqualTo("unsupported-media-type");
        assertThat(ApiExceptionHandler.slugFor(HttpStatus.BAD_REQUEST)).isEqualTo("validation-failed");
        assertThat(ApiExceptionHandler.slugFor(HttpStatus.INTERNAL_SERVER_ERROR)).isEqualTo("internal-error");
    }
}
