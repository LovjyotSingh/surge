package dev.surge.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.booking.support.Identity;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.common.security.IdentityTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * booking-service trusts only a verified {@code X-Surge-Identity} addressed to it (SEC-03). Rules, mirroring the
 * gateway: admin a gateway ADMIN; event reads and the seat stream any gateway request (anonymous included); holds and
 * orders a gateway user. Only the gateway is a trusted issuer until S3 adds {@code /internal/orders} for notification.
 */
@IntegrationTest
class BookingSecurityTest {

    private static final String PROBLEM = "https://surge.dev/problems/";
    private static final String INTEGRITY = "/api/bookings/admin/events/1/integrity";

    @Autowired
    MockMvc mvc;

    /** A hold request whose body would fail validation: reaching the controller shows as 400, not 2xx. */
    private static MockHttpServletRequestBuilder hold() {
        return post("/api/bookings/holds").contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    @Test
    void forgedUserHeadersWithoutATokenAre401() throws Exception {
        mvc.perform(hold().header("X-User-Id", "u-1")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        mvc.perform(get(INTEGRITY).header("X-User-Id", "u-admin").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/orders/1").header("X-User-Id", "u-1")).andExpect(status().isUnauthorized());
        // public reads too: only the gateway (which signs an anonymous token) may call booking
        mvc.perform(get("/api/bookings/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/events/1/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedHeadersNeverUpgradeAValidToken() throws Exception {
        mvc.perform(hold().with(Identity.anonymous()).header("X-User-Id", "u-1")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
        mvc.perform(get(INTEGRITY).with(Identity.user("u-1")).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aGatewayTokenForAnotherServiceIs401() throws Exception {
        mvc.perform(hold().with(IdentityTokens.asOrdinaryUser("catalog-service", "u-1")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(INTEGRITY).with(IdentityTokens.asAdmin("payment-service", "u-admin")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/events").with(IdentityTokens.asAnonymous("notification-service")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceTokensAreNotTrustedYet() throws Exception {
        for (String issuer : new String[] {"svc:notification", "svc:booking"}) {
            mvc.perform(get("/api/bookings/orders/1").with(Identity.service(issuer)))
                    .andExpect(status().isUnauthorized());
            mvc.perform(hold().with(Identity.service(issuer))).andExpect(status().isUnauthorized());
            mvc.perform(get(INTEGRITY).with(Identity.service(issuer))).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void anAnonymousGatewayTokenIsNoUser() throws Exception {
        mvc.perform(hold().with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
        mvc.perform(post("/api/bookings/orders").with(Identity.anonymous()).header("Idempotency-Key", "k-sec")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
        mvc.perform(get("/api/bookings/orders/1").with(Identity.anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(get(INTEGRITY).with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
    }

    @Test
    void aUserTokenOnAdminIs403() throws Exception {
        mvc.perform(get(INTEGRITY).with(Identity.user("u-1"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
        mvc.perform(get(INTEGRITY).with(Identity.userWithRoles("u-1", "USER", "SUPERADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings/admin/events/1/reset").with(Identity.user("u-1")))
                .andExpect(status().isForbidden());
        mvc.perform(get(INTEGRITY).with(Identity.admin())).andExpect(status().isOk());
    }

    @Test
    void aSignedInUserReachesTheControllers() throws Exception {
        mvc.perform(hold().with(Identity.user("u-sec"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM + "validation-failed"));
    }

    @Test
    void publicReadsTakeTheGatewaysAnonymousToken() throws Exception {
        mvc.perform(get("/api/bookings/events").with(Identity.anonymous())).andExpect(status().isOk());
        mvc.perform(get("/api/bookings/events/1").with(Identity.anonymous())).andExpect(status().isOk());
        // the stream route too (an unknown event, so no stream is left open)
        mvc.perform(get("/api/bookings/events/9999/stream").with(Identity.anonymous()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnmappedPathIs401WithoutAToken() throws Exception {
        mvc.perform(get("/api/bookings/nope")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
        mvc.perform(get("/internal/orders/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyHealthIsOpenOnActuator() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }
}
