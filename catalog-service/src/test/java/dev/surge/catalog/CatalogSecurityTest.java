package dev.surge.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.catalog.error.ApiExceptionHandler;
import dev.surge.catalog.support.Identity;
import dev.surge.catalog.support.IntegrationTest;
import dev.surge.catalog.web.AdminAccess;
import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalIdentityException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * catalog-service trusts only a verified {@code X-Surge-Identity} addressed to it (SEC-03). Rules, mirroring the
 * gateway: {@code /api/catalog/admin/**} a gateway ADMIN; every other GET under {@code /api/catalog} any gateway
 * request (anonymous included), and one event by id also {@code svc:notification} (the ticket). Anything else is
 * denied.
 */
@IntegrationTest
class CatalogSecurityTest {

    private static final String PROBLEM = "https://surge.dev/problems/";
    private static final String STATS = "/api/catalog/admin/stats/daily";
    private static final String AUDIENCE = Identity.AUDIENCE;

    @Autowired
    MockMvc mvc;

    /** An import request whose (empty) file the controller would reject: reaching it shows as 400. */
    private static MockMultipartHttpServletRequestBuilder importEmpty() {
        return multipart("/api/catalog/admin/events/import").file(new MockMultipartFile("file", "e.csv", "text/csv",
                new byte[0]));
    }

    @Test
    void forgedUserHeadersWithoutATokenAre401() throws Exception {
        mvc.perform(get(STATS).param("eventId", "2").header("X-User-Id", "u-admin").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
        mvc.perform(importEmpty().header("X-User-Roles", "ADMIN")).andExpect(status().isUnauthorized());
        // public reads too: only the gateway (which signs an anonymous token) may call catalog
        mvc.perform(get("/api/catalog/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/catalog/events/search").param("q", "x")).andExpect(status().isUnauthorized());
    }

    @Test
    void aGatewayTokenForAnotherServiceIs401() throws Exception {
        mvc.perform(get(STATS).param("eventId", "2").with(IdentityTokens.asAdmin("booking-service", "u-admin")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/catalog/events").with(IdentityTokens.asAnonymous("booking-service")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void notificationReadsOneEventByIdAndNothingElse() throws Exception {
        mvc.perform(get("/api/catalog/events/2").with(Identity.service("svc:notification")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(2));
        // the list, search and admin stay the gateway's
        mvc.perform(get("/api/catalog/events").with(Identity.service("svc:notification")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
        mvc.perform(get("/api/catalog/events/search").param("q", "x").with(Identity.service("svc:notification")))
                .andExpect(status().isForbidden());
        mvc.perform(get(STATS).param("eventId", "2").with(Identity.service("svc:notification")))
                .andExpect(status().isForbidden());
        // read-only, and only with a token addressed to catalog
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/catalog/events/2")
                .with(Identity.service("svc:notification"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/catalog/events/2").with(IdentityTokens.asService("svc:notification", "booking-service")))
                .andExpect(status().isUnauthorized());
        // booking is not a trusted issuer of catalog at all
        mvc.perform(get("/api/catalog/events/2").with(Identity.service("svc:booking")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(STATS).param("eventId", "2").with(Identity.service("svc:booking")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAnonymousGatewayTokenIsNoAdmin() throws Exception {
        mvc.perform(get(STATS).param("eventId", "2").with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
        mvc.perform(importEmpty().with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
    }

    @Test
    void aUserTokenOnAdminIs403() throws Exception {
        mvc.perform(get(STATS).param("eventId", "2").with(Identity.user("u-1"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
        mvc.perform(importEmpty().with(Identity.userWithRoles("u-1", "USER", "SUPERADMIN")))
                .andExpect(status().isForbidden());
        // a forged role header does not upgrade a valid user token
        mvc.perform(get(STATS).param("eventId", "2").with(Identity.user("u-1")).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isForbidden());
        mvc.perform(importEmpty().with(Identity.admin())).andExpect(status().isBadRequest());
    }

    @Test
    void publicReadsTakeTheGatewaysAnonymousToken() throws Exception {
        mvc.perform(get("/api/catalog/events").with(Identity.anonymous())).andExpect(status().isOk());
        mvc.perform(get("/api/catalog/events/2").with(Identity.anonymous())).andExpect(status().isOk());
    }

    @Test
    void anUnmappedPathIs401WithoutAToken() throws Exception {
        mvc.perform(get("/api/catalog/nope")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
        mvc.perform(multipart("/api/catalog/events").file(new MockMultipartFile("file", "x".getBytes(
                StandardCharsets.UTF_8)))).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/events/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void anythingNoRuleNamesIsDeniedEvenWithAToken() throws Exception {
        mvc.perform(multipart("/api/catalog/events").file(new MockMultipartFile("file", "x".getBytes(
                StandardCharsets.UTF_8))).with(Identity.admin())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/events/1").with(Identity.service("svc:notification")))
                .andExpect(status().isForbidden());
        // the read rules are GET-only: any other method is refused here, never an MVC 405
        for (HttpMethod method : java.util.List.of(HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.PUT,
                HttpMethod.DELETE)) {
            mvc.perform(request(method, "/api/catalog/events").with(Identity.user("u-1")))
                    .andExpect(status().isForbidden());
            mvc.perform(request(method, "/api/catalog/events/2").with(Identity.anonymous()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void onlyHealthIsOpenOnActuator() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        // denied with any token too, until phase 3 adds a scrape identity
        mvc.perform(get("/actuator/prometheus").with(Identity.admin())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/info").with(Identity.admin())).andExpect(status().isForbidden());
    }

    // ---- the second layer: the admin interceptor reads only the verified identity ----------------------------------

    private static void admin() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", STATS);
        request.addHeader("X-User-Roles", "ADMIN");
        new AdminAccess().preHandle(request, new MockHttpServletResponse(), new Object());
    }

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InternalIdentityException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void theAdminInterceptorNeedsAGatewayAdmin() {
        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE, CatalogSecurityTest::admin);
        IdentityTokens.runAs(null, AUDIENCE, () -> assertStatus(CatalogSecurityTest::admin, 401));
        IdentityTokens.runAs(IdentityTokens.anonymous(AUDIENCE), AUDIENCE,
                () -> assertStatus(CatalogSecurityTest::admin, 401));
        IdentityTokens.runAs(IdentityTokens.ordinaryUser(AUDIENCE, "u-1"), AUDIENCE,
                () -> assertStatus(CatalogSecurityTest::admin, 403));
        IdentityTokens.runAs(IdentityTokens.service("svc:notification", AUDIENCE), AUDIENCE,
                () -> assertStatus(CatalogSecurityTest::admin, 403));
    }

    /** catalog's handler has a catch-all for Exception: the identity problems must not fall into it (500). */
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
