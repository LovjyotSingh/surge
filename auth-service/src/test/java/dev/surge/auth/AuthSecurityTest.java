package dev.surge.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.auth.support.Identity;
import dev.surge.auth.support.IntegrationTest;
import dev.surge.common.security.IdentityTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * auth-service trusts only a verified {@code X-Surge-Identity} addressed to it (SEC-03). Rules, mirroring the
 * gateway: JWKS open (the gateway fetches it directly); login/signup any gateway request; {@code /me} a gateway user;
 * {@code /api/auth/admin/**} a gateway ADMIN; {@code /internal/users/*} only {@code svc:notification}.
 */
@IntegrationTest
class AuthSecurityTest {

    private static final String PROBLEM = "https://surge.dev/problems/";
    private static final String MINT = "/api/auth/admin/test-tokens";
    private static final String BODY = "{\"prefix\":\"lt-sec\",\"count\":1}";

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder mint() {
        return post(MINT).contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    private static MockHttpServletRequestBuilder login() {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}");
    }

    @Test
    void forgedUserHeadersWithoutATokenAre401() throws Exception {
        mvc.perform(get("/api/auth/me").header("X-User-Id", "u-1").header("X-User-Roles", "USER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
        mvc.perform(mint().header("X-User-Id", "u-admin").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/users/u-1").header("X-User-Id", "u-1")).andExpect(status().isUnauthorized());
        // public entry points too: only the gateway (which signs an anonymous token) may call them
        mvc.perform(login()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
    }

    @Test
    void aGatewayTokenForAnotherServiceIs401() throws Exception {
        mvc.perform(get("/api/auth/me").with(IdentityTokens.asOrdinaryUser("booking-service", "u-1")))
                .andExpect(status().isUnauthorized());
        mvc.perform(mint().with(IdentityTokens.asAdmin("catalog-service", "u-admin")))
                .andExpect(status().isUnauthorized());
        mvc.perform(login().with(IdentityTokens.asAnonymous("payment-service"))).andExpect(status().isUnauthorized());
    }

    @Test
    void serviceTokensAreNeverUsers() throws Exception {
        // svc:notification is trusted here, but only for /internal/users
        mvc.perform(get("/api/auth/me").with(Identity.service("svc:notification"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
        mvc.perform(mint().with(Identity.service("svc:notification"))).andExpect(status().isForbidden());
        mvc.perform(login().with(Identity.service("svc:notification"))).andExpect(status().isForbidden());
        // svc:booking is not a trusted issuer of auth-service at all
        mvc.perform(get("/api/auth/me").with(Identity.service("svc:booking"))).andExpect(status().isUnauthorized());
    }

    @Test
    void anAnonymousGatewayTokenIsNoUser() throws Exception {
        mvc.perform(get("/api/auth/me").with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
        mvc.perform(mint().with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
    }

    @Test
    void aUserTokenOnAdminIs403() throws Exception {
        mvc.perform(mint().with(Identity.user("u-5"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
        mvc.perform(mint().with(Identity.userWithRoles("u-5", "USER", "SUPERADMIN"))).andExpect(status().isForbidden());
        mvc.perform(mint().with(Identity.admin())).andExpect(status().isOk());
    }

    @Test
    void internalUsersAreOnlyForNotification() throws Exception {
        mvc.perform(get("/internal/users/lt-sec-1").with(Identity.service("svc:notification")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("lt-sec-1"));
        mvc.perform(get("/internal/users/lt-sec-1").with(Identity.admin())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/users/lt-sec-1").with(Identity.user("u-1"))).andExpect(status().isForbidden());
        mvc.perform(get("/internal/users/lt-sec-1").with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
        mvc.perform(get("/internal/users/lt-sec-1").with(Identity.service("svc:booking")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/users/lt-sec-1")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicEntryPointsTakeTheGatewaysAnonymousToken() throws Exception {
        // through the chain to the controller: a wrong password is the controller's 401 bad-credentials
        mvc.perform(login().with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "bad-credentials"));
        mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void jwksIsOpenForTheGateway() throws Exception {
        mvc.perform(get("/api/auth/.well-known/jwks.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"));
    }

    @Test
    void anUnmappedPathIs401WithoutAToken() throws Exception {
        mvc.perform(get("/internal/whatever")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
        mvc.perform(get("/nope")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/.well-known/jwks.json")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyHealthIsOpenOnActuator() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(Identity.anonymous())).andExpect(status().isOk());
    }
}
