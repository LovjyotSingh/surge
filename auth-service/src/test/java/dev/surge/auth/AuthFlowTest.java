package dev.surge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import dev.surge.auth.api.LoginRequest;
import dev.surge.auth.api.SignupRequest;
import dev.surge.auth.support.Identity;
import dev.surge.auth.support.IntegrationTest;
import dev.surge.auth.user.Role;
import dev.surge.auth.user.User;
import dev.surge.auth.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IntegrationTest
class AuthFlowTest {

    /** notification-service's own token: the only caller /internal/users accepts. */
    private static final RequestPostProcessor NOTIFICATION = Identity.service("svc:notification");

    @Autowired
    MockMvc mvc;

    static String email() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    String body(String email, String password, String name) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"displayName\":\"" + name + "\"}";
    }

    String creds(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    MvcResult signup(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                .content(body(email, password, "Ada"))).andReturn();
    }

    @Test
    void signupLoginAndMe() throws Exception {
        String email = email();
        mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email.toUpperCase(), "correct-horse", "Ada")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.roles[0]").value("USER"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("hash"))));

        MvcResult login = mvc.perform(post("/api/auth/login").with(Identity.anonymous())
                .contentType(MediaType.APPLICATION_JSON)
                        .content(creds(email, "correct-horse")))
                .andExpect(status().isOk()).andReturn();
        String id = JsonPath.read(login.getResponse().getContentAsString(), "$.user.id");
        assertThat(id).matches("u-\\d+");

        mvc.perform(get("/api/auth/me").with(Identity.user(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.displayName").value("Ada"))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    void meNeedsASignedInUser() throws Exception {
        mvc.perform(get("/api/auth/me").with(Identity.anonymous())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/missing-user"));
        // a legacy header is not an identity: without a token the chain answers before any controller
        mvc.perform(get("/api/auth/me").header("X-User-Id", "u-1")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/unauthorized"));
    }

    @Test
    void duplicateEmailIs409() throws Exception {
        String email = email();
        assertThat(signup(email, "password-1").getResponse().getStatus()).isEqualTo(201);
        mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email.toUpperCase(), "password-2", "Bob")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/email-taken"));
    }

    @Test
    void signupValidation() throws Exception {
        mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-an-email", "password-1", "Ada")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email(), "short", "Ada")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email(), "x".repeat(73), "Ada")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
        String email = email();
        signup(email, "right-password");
        String wrong = mvc.perform(post("/api/auth/login").with(Identity.anonymous())
                .contentType(MediaType.APPLICATION_JSON)
                        .content(creds(email, "wrong-password")))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(post("/api/auth/login").with(Identity.anonymous())
                .contentType(MediaType.APPLICATION_JSON)
                        .content(creds(email(), "wrong-password")))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        for (String field : List.of("type", "title", "detail", "status")) {
            Object a = JsonPath.read(wrong, "$." + field);
            Object b = JsonPath.read(unknown, "$." + field);
            assertThat(a).isEqualTo(b);
        }
        String type = JsonPath.read(wrong, "$.type");
        assertThat(type).isEqualTo("https://surge.dev/problems/bad-credentials");
    }

    @Test
    void adminIsSeededAndCanLogIn() throws Exception {
        mvc.perform(post("/api/auth/login").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(creds("admin@surge.local", "surge-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.roles[0]").value("USER"))
                .andExpect(jsonPath("$.user.roles[1]").value("ADMIN"));
    }

    private static void seed(UserRepository users, org.springframework.security.crypto.password.PasswordEncoder encoder, java.time.Clock clock, String password) {
        new dev.surge.auth.user.AdminSeeder(users, encoder, clock, "admin@surge.local", password, "dev").run(null);
    }

    /** Rotation end to end on the shared context: the seeder re-hashes, the new password logs in, the old one is 401. */
    @Test
    void rotatingTheAdminPasswordLocksOutTheOldOne(@Autowired UserRepository users,
            @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder,
            @Autowired java.time.Clock clock) throws Exception {
        try {
            seed(users, encoder, clock, "rotated-admin-password");
            mvc.perform(post("/api/auth/login").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                            .content(creds("admin@surge.local", "rotated-admin-password")))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/auth/login").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                            .content(creds("admin@surge.local", "surge-admin")))
                    .andExpect(status().isUnauthorized());
        } finally {
            seed(users, encoder, clock, "surge-admin");
        }
        mvc.perform(post("/api/auth/login").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(creds("admin@surge.local", "surge-admin")))
                .andExpect(status().isOk());
    }

    @Test
    void ordinaryLoginTokensAreNotLoadTestTokens() throws Exception {
        String json = mvc.perform(post("/api/auth/login").with(Identity.anonymous())
                .contentType(MediaType.APPLICATION_JSON)
                        .content(creds("admin@surge.local", "surge-admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(json, "$.token");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload).doesNotContain("\"lt\"");
    }

    @Test
    void multibytePasswordOver72BytesIs400NotA500() throws Exception {
        String pw = "\u00e9".repeat(72);
        mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email(), pw, "Ada")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.errors.password").exists());
        // 36 x 2 bytes = exactly 72 bytes is fine
        mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(email(), "\u00e9".repeat(36), "Ada")))
                .andExpect(status().isCreated());
    }

    @Test
    void signupCannotTakeTheAdminEmail() throws Exception {
        mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Admin@Surge.local", "password-1", "Evil")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/email-taken"));
    }

    @Test
    void emailsDifferingOnlyByAccentAreDifferentAccounts(@Autowired UserRepository users) {
        String tag = UUID.randomUUID().toString();
        users.saveAndFlush(new User("jose-" + tag + "@example.com", "x", "a", Role.USER, Instant.now()));
        users.saveAndFlush(new User("jos\u00e9-" + tag + "@example.com", "x", "b", Role.USER, Instant.now()));
        assertThat(users.findByEmail("jos\u00e9-" + tag + "@example.com")).isPresent();
    }

    @Test
    void requestRecordsRedactPassword() {
        assertThat(new SignupRequest("a@b.c", "secret-pw", "n").toString()).doesNotContain("secret-pw");
        assertThat(new LoginRequest("a@b.c", "secret-pw").toString()).doesNotContain("secret-pw");
    }

    @Test
    void tokenVerifiesAgainstJwksWithExpectedClaims() throws Exception {
        String email = email();
        MvcResult res = signup(email, "password-1");
        String token = JsonPath.read(res.getResponse().getContentAsString(), "$.token");
        String id = JsonPath.read(res.getResponse().getContentAsString(), "$.user.id");

        String jwks = mvc.perform(get("/api/auth/.well-known/jwks.json")).andReturn().getResponse()
                .getContentAsString();
        RSAKey key = (RSAKey) JWKSet.parse(jwks).getKeys().get(0);
        Jwt jwt = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(token);

        assertThat(jwt.getClaimAsString("iss")).isEqualTo("surge-auth");
        assertThat(jwt.getSubject()).isEqualTo(id);
        assertThat(jwt.<String>getClaim("email")).isEqualTo(email);
        assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("USER");
        assertThat(jwt.getHeaders().get("kid")).isEqualTo(key.getKeyID());
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(60));
        assertThat(jwt.getIssuedAt().getNano()).isZero();
        String expiresAt = JsonPath.read(res.getResponse().getContentAsString(), "$.expiresAt");
        assertThat(Instant.parse(expiresAt)).isEqualTo(jwt.getExpiresAt());
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(59)));
    }

    @Test
    void internalLookup() throws Exception {
        MvcResult res = signup(email(), "password-1");
        String id = JsonPath.read(res.getResponse().getContentAsString(), "$.user.id");
        mvc.perform(get("/internal/users/" + id).with(NOTIFICATION)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.roles[0]").value("USER"));
        mvc.perform(get("/internal/users/lt-run1-7").with(NOTIFICATION)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("lt-run1-7"))
                .andExpect(jsonPath("$.displayName").value("lt-run1-7"))
                .andExpect(jsonPath("$.email").value("lt-run1-7@loadtest.local"))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
        mvc.perform(get("/internal/users/u-99999999").with(NOTIFICATION)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/user-not-found"));
        mvc.perform(get("/internal/users/u-abc").with(NOTIFICATION)).andExpect(status().isNotFound());
        mvc.perform(get("/internal/users/whoever").with(NOTIFICATION)).andExpect(status().isNotFound());
    }
}
