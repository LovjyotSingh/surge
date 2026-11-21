package dev.surge.booking.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.booking.payment.PaymentGateway;
import dev.surge.booking.support.Identity;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * A Redis or database resource failure is a 503 {@code service-unavailable} with {@code Retry-After: 1}, not the
 * catch-all 500; lock conflicts stay out of it ({@link ApiExceptionHandler#resourceUnavailable}).
 */
@IntegrationTest
class ResourceUnavailableTest {

    private static final String TYPE = "https://surge.dev/problems/service-unavailable";

    @Autowired MockMvc mvc;
    @Autowired TestData testData;
    /** The type-level spy from @IntegrationTest. */
    @Autowired PaymentGateway gateway;

    @BeforeEach
    void reset() {
        testData.resetEvent(1L);
    }

    private String hold() throws Exception {
        MvcResult held = mvc.perform(post("/api/bookings/holds").with(Identity.user("u1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatIds\":[1]}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(held.getResponse().getContentAsString(), "$.holdId");
    }

    private MvcResult checkoutFailingWith(String holdId, RuntimeException failure) throws Exception {
        doThrow(failure).when(gateway).charge(any());
        return mvc.perform(post("/api/bookings/orders").with(Identity.user("u1")).header("Idempotency-Key", "key-0503")
                        .header("X-Request-Id", "req-503")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"holdId\":\"" + holdId + "\"}"))
                .andReturn();
    }

    @Test
    void resourceFailuresAre503WithRetryAfterOne() throws Exception {
        String holdId = hold();
        for (RuntimeException failure : new RuntimeException[] {
                new RedisConnectionFailureException("redis down"),
                new QueryTimeoutException("redis command timed out"),
                new CannotGetJdbcConnectionException("pool exhausted", new SQLTransientConnectionException("timeout")),
                new DataAccessResourceFailureException("db gone"),
                new CannotCreateTransactionException("no connection")}) {
            MvcResult r = checkoutFailingWith(holdId, failure);
            String body = r.getResponse().getContentAsString();
            assertThat(r.getResponse().getStatus()).as(failure.getClass().getSimpleName()).isEqualTo(503);
            assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("1");
            assertThat(r.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat((String) JsonPath.read(body, "$.type")).isEqualTo(TYPE);
            assertThat((String) JsonPath.read(body, "$.requestId")).isEqualTo("req-503");
            assertThat(body).doesNotContain("redis down").doesNotContain("db gone");
        }
    }

    @Test
    void lockConflictsAreNotResourceFailures() throws Exception {
        String holdId = hold();
        for (RuntimeException failure : new RuntimeException[] {
                new OptimisticLockingFailureException("stale"), new CannotAcquireLockException("lock wait")}) {
            assertThat(checkoutFailingWith(holdId, failure).getResponse().getStatus())
                    .as(failure.getClass().getSimpleName()).isNotEqualTo(503);
        }
    }
}
