package dev.surge.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalIdentityException;
import dev.surge.notification.audit.AuditController;
import dev.surge.notification.error.ApiExceptionHandler;
import dev.surge.notification.error.TicketNotFoundException;
import dev.surge.notification.messaging.DlqController;
import dev.surge.notification.support.Identity;
import dev.surge.notification.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * notification-service trusts only a verified {@code X-Surge-Identity} addressed to it (SEC-03). Rules, mirroring the
 * gateway: {@code /api/notifications/admin/**} a gateway ADMIN; {@code GET /api/notifications/tickets/**} a gateway
 * user. Only the gateway is a trusted issuer: notification receives no service-to-service calls.
 */
@IntegrationTest
class NotificationSecurityTest {

    private static final String PROBLEM = "https://surge.dev/problems/";
    private static final String TICKET = "/api/notifications/tickets/1.pdf";
    private static final String DLQ = "/api/notifications/admin/dlq";
    private static final String AUDIT = "/api/notifications/admin/audit";
    private static final String AUDIENCE = Identity.AUDIENCE;

    @Autowired
    MockMvc mvc;
    @Autowired
    TicketController tickets;
    @Autowired
    DlqController dlq;
    @Autowired
    AuditController audit;

    @Test
    void forgedUserHeadersWithoutATokenAre401() throws Exception {
        mvc.perform(get(TICKET).header("X-User-Id", "u-1")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
        mvc.perform(get(DLQ).header("X-User-Id", "u-admin").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(DLQ + "/replay").header("X-User-Roles", "ADMIN")).andExpect(status().isUnauthorized());
    }

    @Test
    void aGatewayTokenForAnotherServiceIs401() throws Exception {
        mvc.perform(get(TICKET).with(IdentityTokens.asOrdinaryUser("booking-service", "u-1")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(AUDIT).with(IdentityTokens.asAdmin("catalog-service", "u-admin")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceTokensAreNotTrusted() throws Exception {
        for (String issuer : new String[] {"svc:booking", "svc:notification"}) {
            mvc.perform(get(TICKET).with(Identity.service(issuer))).andExpect(status().isUnauthorized());
            mvc.perform(get(DLQ).with(Identity.service(issuer))).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void anAnonymousGatewayTokenIsNoUser() throws Exception {
        mvc.perform(get(TICKET).with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
        mvc.perform(get(AUDIT).with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
    }

    @Test
    void aUserTokenOnAdminIs403() throws Exception {
        mvc.perform(get(DLQ).with(Identity.user("u-1"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
        mvc.perform(post(DLQ + "/replay").with(Identity.userWithRoles("u-1", "USER", "SUPERADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get(AUDIT).with(Identity.user("u-1")).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isForbidden());
        mvc.perform(get(AUDIT).with(Identity.admin())).andExpect(status().isOk());
    }

    @Test
    void aSignedInUserReachesTheTicketController() throws Exception {
        mvc.perform(get("/api/notifications/tickets/987654321.pdf").with(Identity.user("u-1")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value(PROBLEM + "ticket-not-found"));
    }

    @Test
    void anUnmappedPathIs401WithoutAToken() throws Exception {
        mvc.perform(get("/api/notifications/nope")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
        mvc.perform(get("/internal/tickets/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyHealthIsOpenOnActuator() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }

    // ---- the second layer: the controllers read only the verified identity ----------------------------------------

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InternalIdentityException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void theTicketControllerChecksTheUserItself() {
        Runnable download = () -> tickets.download(987654321L);
        IdentityTokens.runAs(IdentityTokens.ordinaryUser(AUDIENCE, "u-1"), AUDIENCE,
                () -> assertThatThrownBy(download::run).isInstanceOf(TicketNotFoundException.class));
        IdentityTokens.runAs(null, AUDIENCE, () -> assertStatus(download, 401));
        IdentityTokens.runAs(IdentityTokens.anonymous(AUDIENCE), AUDIENCE, () -> assertStatus(download, 401));
        IdentityTokens.runAs(IdentityTokens.service("svc:booking", AUDIENCE), AUDIENCE,
                () -> assertStatus(download, 401));
    }

    @Test
    void theAdminControllersCheckTheAdminThemselves() {
        Runnable list = () -> dlq.list("1");
        Runnable replay = () -> dlq.replay("1");
        Runnable tail = () -> audit.audit(null, "1");
        for (Runnable call : new Runnable[] {list, replay, tail}) {
            IdentityTokens.runAs(null, AUDIENCE, () -> assertStatus(call, 401));
            IdentityTokens.runAs(IdentityTokens.anonymous(AUDIENCE), AUDIENCE, () -> assertStatus(call, 401));
            IdentityTokens.runAs(IdentityTokens.ordinaryUser(AUDIENCE, "u-1"), AUDIENCE, () -> assertStatus(call, 403));
            IdentityTokens.runAs(IdentityTokens.service("svc:notification", AUDIENCE), AUDIENCE,
                    () -> assertStatus(call, 403));
        }
        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE, tail);
    }

    /** notification's handler has a catch-all for Exception: the identity problems must not fall into it (500). */
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
