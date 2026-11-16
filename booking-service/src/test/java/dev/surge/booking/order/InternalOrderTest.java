package dev.surge.booking.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.booking.support.Identity;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import dev.surge.common.security.IdentityTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S3: notification reads an order for the ticket through {@code GET /internal/orders/{id}?userId=...}, signed as
 * {@code svc:notification}, instead of impersonating the user. Nobody else may call it, and it answers only for the
 * order's owner.
 */
@IntegrationTest
class InternalOrderTest {

    private static final String PROBLEM = "https://surge.dev/problems/";
    private static final String OWNER = "u-int-1";

    @Autowired MockMvc mvc;
    @Autowired TestData testData;
    @Autowired JdbcTemplate jdbc;

    private int orderId;

    @BeforeEach
    void anOrder() throws Exception {
        testData.resetEvent(1L);
        jdbc.update("DELETE FROM outbox");
        MvcResult hold = mvc.perform(post("/api/bookings/holds").with(Identity.user(OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatIds\":[1,2]}"))
                .andExpect(status().isCreated()).andReturn();
        String holdId = JsonPath.read(hold.getResponse().getContentAsString(), "$.holdId");
        MvcResult order = mvc.perform(post("/api/bookings/orders").with(Identity.user(OWNER))
                        .header("Idempotency-Key", "int-" + holdId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"" + holdId + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        orderId = JsonPath.read(order.getResponse().getContentAsString(), "$.orderId");
    }

    private String internal() {
        return "/internal/orders/" + orderId;
    }

    @Test
    void notificationReadsTheOwnersOrder() throws Exception {
        String asOwner = mvc.perform(get("/api/bookings/orders/" + orderId).with(Identity.user(OWNER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        mvc.perform(get(internal()).param("userId", OWNER).with(Identity.service("svc:notification")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.lines.length()").value(2))
                // the same view the owner gets
                .andExpect(content().json(asOwner));
    }

    @Test
    void someoneElsesOrMissingOrderIs404() throws Exception {
        mvc.perform(get(internal()).param("userId", "u-int-2").with(Identity.service("svc:notification")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(PROBLEM + "order-not-found"));
        mvc.perform(get("/internal/orders/987654321").param("userId", OWNER)
                        .with(Identity.service("svc:notification")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theUserIdMustBeAUserId() throws Exception {
        for (String bad : new String[] {"", "a b", "../x", "x".repeat(65), "svc:notification"}) {
            mvc.perform(get(internal()).param("userId", bad).with(Identity.service("svc:notification")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "validation-failed"));
        }
        mvc.perform(get(internal()).with(Identity.service("svc:notification"))).andExpect(status().isBadRequest());
    }

    @Test
    void nobodyButNotificationMayCallIt() throws Exception {
        // booking's own key is not a trusted issuer here at all
        mvc.perform(get(internal()).param("userId", OWNER).with(Identity.service("svc:booking")))
                .andExpect(status().isUnauthorized());
        // the gateway, however privileged the user: the gateway never routes /internal, and no user may read this
        mvc.perform(get(internal()).param("userId", OWNER).with(Identity.anonymous()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(internal()).param("userId", OWNER).with(Identity.user(OWNER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
        mvc.perform(get(internal()).param("userId", OWNER).with(Identity.admin())).andExpect(status().isForbidden());
        // notification's own token, but addressed to another service
        mvc.perform(get(internal()).param("userId", OWNER)
                        .with(IdentityTokens.asService("svc:notification", "auth-service")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(internal()).param("userId", OWNER).header("X-User-Id", OWNER))
                .andExpect(status().isUnauthorized());
        // read-only: any other method is refused before MVC
        mvc.perform(post(internal()).param("userId", OWNER).with(Identity.service("svc:notification")))
                .andExpect(status().isForbidden());
    }

    @Test
    void notificationIsNoUserAndNoAdmin() throws Exception {
        mvc.perform(get("/api/bookings/orders/" + orderId).with(Identity.service("svc:notification")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings/admin/events/1/reset").with(Identity.service("svc:notification")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
        mvc.perform(get("/api/bookings/admin/events/1/integrity").with(Identity.service("svc:notification")))
                .andExpect(status().isForbidden());
    }
}
