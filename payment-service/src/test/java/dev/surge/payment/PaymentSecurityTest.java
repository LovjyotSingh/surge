package dev.surge.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalIdentityException;
import dev.surge.payment.error.ApiExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * payment-service trusts only a verified {@code X-Surge-Identity} addressed to it (SEC-03). Rules, mirroring the
 * gateway: {@code /api/payments/admin/**} a gateway ADMIN; the rest of {@code /api/payments/**} (charge, status,
 * refund) only {@code svc:booking}, which booking signs from S3 on. The gateway never routes the payments API.
 */
@IntegrationTest
class PaymentSecurityTest {

    private static final String PROBLEM = "https://surge.dev/problems/";
    private static final String CHAOS = "/api/payments/admin/chaos";

    @Autowired
    MockMvc mvc;
    @Autowired
    ChaosController chaos;
    @Autowired
    PaymentController payments;

    private static MockHttpServletRequestBuilder charge() {
        return post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"sec:" + UUID.randomUUID() + "\",\"userId\":\"u-1\",\"amountInr\":10}");
    }

    @Test
    void forgedUserHeadersWithoutATokenAre401() throws Exception {
        mvc.perform(get(CHAOS).header("X-User-Id", "u-admin").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
        mvc.perform(charge().header("X-User-Id", "u-1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/payments/pay_x/refund")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenForAnotherServiceIs401() throws Exception {
        mvc.perform(get(CHAOS).with(IdentityTokens.asAdmin("booking-service", "u-admin")))
                .andExpect(status().isUnauthorized());
        // booking's own token, but addressed to another service
        mvc.perform(charge().with(IdentityTokens.asService("svc:booking", "catalog-service")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyBookingMayUseThePaymentsApi() throws Exception {
        mvc.perform(charge().with(Identity.booking())).andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true));
        // not a trusted issuer here at all
        mvc.perform(charge().with(Identity.service("svc:notification"))).andExpect(status().isUnauthorized());
        // a gateway identity, however privileged, is not booking
        mvc.perform(charge().with(Identity.admin())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
        mvc.perform(charge().with(Identity.user("u-1"))).andExpect(status().isForbidden());
        mvc.perform(charge().with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
        mvc.perform(post("/api/payments/pay_x/refund").with(Identity.admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/payments").param("reference", "x").with(Identity.user("u-1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aServiceTokenIsNeverAnAdmin() throws Exception {
        mvc.perform(get(CHAOS).with(Identity.booking())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
    }

    @Test
    void anAnonymousGatewayTokenIsNoUser() throws Exception {
        mvc.perform(get(CHAOS).with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
    }

    @Test
    void aUserTokenOnAdminIs403() throws Exception {
        mvc.perform(get(CHAOS).with(Identity.user("u-1"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
        mvc.perform(get(CHAOS).with(Identity.userWithRoles("u-1", "USER", "SUPERADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get(CHAOS).with(Identity.user("u-1")).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isForbidden());
        mvc.perform(get(CHAOS).with(Identity.admin())).andExpect(status().isOk());
    }

    @Test
    void anUnmappedPathIs401WithoutAToken() throws Exception {
        mvc.perform(get("/nope")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
        mvc.perform(get("/internal/payments")).andExpect(status().isUnauthorized());
    }

    @Test
    void anythingNoRuleNamesIsDeniedEvenToBooking() throws Exception {
        // the payments API is three operations; anything else is refused before MVC, even with booking's token
        mvc.perform(put("/api/payments").with(Identity.booking())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
        mvc.perform(get("/api/payments/pay_x").with(Identity.booking())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/payments/pay_x").with(Identity.booking())).andExpect(status().isForbidden());
        mvc.perform(get("/api/payments/pay_x/refund").with(Identity.booking())).andExpect(status().isForbidden());
        mvc.perform(head("/api/payments").param("reference", "x").with(Identity.booking()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/nope").with(Identity.admin())).andExpect(status().isForbidden());
    }

    @Test
    void onlyHealthIsOpenOnActuator() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        // denied with any token too, until phase 3 adds a scrape identity
        mvc.perform(get("/actuator/prometheus").with(Identity.admin())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/info").with(Identity.booking())).andExpect(status().isForbidden());
    }

    @Test
    void thePaymentsApiChecksTheCallerItself() {
        String audience = Identity.AUDIENCE;
        Runnable find = () -> payments.find("no-such-reference");
        // booking passes the check (and then learns the reference is unknown)
        IdentityTokens.runAs(IdentityTokens.service("svc:booking", audience), audience, () -> assertThatThrownBy(
                find::run).isInstanceOf(dev.surge.payment.error.PaymentNotFoundException.class));
        IdentityTokens.runAs(null, audience, () -> assertStatus(find, 401));
        IdentityTokens.runAs(IdentityTokens.service("svc:notification", audience), audience,
                () -> assertStatus(find, 403));
        IdentityTokens.runAs(IdentityTokens.admin(audience, "u-admin"), audience, () -> assertStatus(find, 403));
        IdentityTokens.runAs(IdentityTokens.anonymous(audience), audience,
                () -> assertStatus(() -> payments.refund("pay_x"), 403));
    }

    // ---- the second layer: the chaos endpoints read only the verified identity ------------------------------------

    private void readChaos() {
        chaos.get();
    }

    private void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InternalIdentityException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void theChaosControllerChecksTheAdminItself() {
        String audience = Identity.AUDIENCE;
        IdentityTokens.runAs(IdentityTokens.admin(audience, "u-admin"), audience, this::readChaos);
        IdentityTokens.runAs(null, audience, () -> assertStatus(this::readChaos, 401));
        IdentityTokens.runAs(IdentityTokens.anonymous(audience), audience, () -> assertStatus(this::readChaos, 401));
        IdentityTokens.runAs(IdentityTokens.ordinaryUser(audience, "u-1"), audience,
                () -> assertStatus(this::readChaos, 403));
        IdentityTokens.runAs(IdentityTokens.service("svc:booking", audience), audience,
                () -> assertStatus(this::readChaos, 403));
    }

    /** payment's handler has a catch-all for Exception: the identity problems must not fall into it (500). */
    @Test
    void theServicesOwnHandlerRendersTheIdentityProblems() throws Exception {
        var resolver = new ExceptionHandlerMethodResolver(ApiExceptionHandler.class);
        var request = new ServletWebRequest(new MockHttpServletRequest());
        for (InternalIdentityException e : new InternalIdentityException[] {InternalIdentityException.missingUser(),
                InternalIdentityException.forbidden()}) {
            assertThat(resolver.resolveMethod(e).getName()).isEqualTo("handleException");
            ResponseEntity<Object> response = new ApiExceptionHandler().handleException(e, request);
            assertThat(response.getStatusCode()).isEqualTo(e.getStatusCode());
            assertThat(((ProblemDetail) response.getBody()).getType().toString()).isEqualTo(
                    PROBLEM + (e.getStatusCode().value() == 401 ? "missing-user" : "forbidden"));
        }
    }
}
