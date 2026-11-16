package dev.surge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.auth.support.Identity;
import dev.surge.auth.support.IntegrationTest;
import dev.surge.auth.support.MailpitContainer;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/** S5: per-account sign-in throttle (SEC-12), password policy (SEC-23) and email verification (SEC-13). */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AccountProtectionTest {

    private static final String PROBLEM = "https://surge.dev/problems/";
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired
    MockMvc mvc;
    @Autowired
    MailpitContainer mailpit;
    @Autowired
    PasswordEncoder encoder;

    private static String email(String tag) {
        return tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private static String signupBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"displayName\":\"Ada\"}";
    }

    private MvcResult signup(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(email, password))).andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        // the gateway would vary X-Forwarded-For per bot; auth keys on the account, so it does not matter here
        return mvc.perform(post("/api/auth/login").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "203.0.113." + (int) (Math.random() * 250))
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    /** Signs up and returns the new user's id. */
    private String signedUp(String email) throws Exception {
        MvcResult res = signup(email, "correct-horse-battery");
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        return JsonPath.read(res.getResponse().getContentAsString(), "$.user.id");
    }

    private String codeFor(String email, int nthEmail) {
        JsonNode message = mailpit.latestTo(email, nthEmail).orElseThrow(() -> new AssertionError("no email"));
        Matcher m = CODE.matcher(message.get("Text").asString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static Boolean ev(String tokenResponse) {
        String token = JsonPath.read(tokenResponse, "$.token");
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return JsonPath.read(payload, "$.ev");
    }

    private MvcResult submitCode(RequestPostProcessor as, String code) throws Exception {
        return mvc.perform(post("/api/auth/verify").with(as).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}")).andReturn();
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    // ---- sign-in throttle ---------------------------------------------------------------------------------------

    @Test
    void sixFailuresForOneEmailFromRotatingIpsGive429ForThatEmail() throws Exception {
        String email = email("throttle");
        signedUp(email);
        for (int i = 1; i <= 5; i++) {
            assertThat(login(email, "wrong-password-" + i).getResponse().getStatus()).as("attempt %d", i)
                    .isEqualTo(401);
        }
        MvcResult sixth = login(email, "wrong-password-6");
        assertThat(sixth.getResponse().getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(sixth.getResponse().getHeader("Retry-After"))).isBetween(1, 30);
        assertThat((String) JsonPath.read(sixth.getResponse().getContentAsString(), "$.type"))
                .isEqualTo(PROBLEM + "login-throttled");
        // locked means locked: the right password waits too
        assertThat(login(email, "correct-horse-battery").getResponse().getStatus()).isEqualTo(429);
        // another account is untouched
        String other = email("other");
        signedUp(other);
        assertThat(login(other, "correct-horse-battery").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anUnknownEmailGetsTheSameAnswersAndTheSameWorkAsAKnownOne() throws Exception {
        String known = email("known");
        signedUp(known);
        String unknown = email("nobody");
        clearInvocations(encoder);

        for (int i = 0; i < 5; i++) {
            String a = login(known, "wrong-password").getResponse().getContentAsString();
            String b = login(unknown, "wrong-password").getResponse().getContentAsString();
            for (String field : List.of("type", "title", "status", "detail")) {
                assertThat((Object) JsonPath.read(a, "$." + field)).isEqualTo(JsonPath.read(b, "$." + field));
            }
        }
        // one BCrypt comparison per attempt for both (the unknown one against a dummy hash)
        verify(encoder, times(10)).matches(anyString(), anyString());

        clearInvocations(encoder);
        MvcResult knownLocked = login(known, "wrong-password");
        MvcResult unknownLocked = login(unknown, "wrong-password");
        for (MvcResult r : List.of(knownLocked, unknownLocked)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(429);
            assertThat(r.getResponse().getHeader("Retry-After")).isNotBlank();
        }
        String a = knownLocked.getResponse().getContentAsString();
        String b = unknownLocked.getResponse().getContentAsString();
        for (String field : List.of("type", "title", "status", "detail")) {
            assertThat((Object) JsonPath.read(a, "$." + field)).isEqualTo(JsonPath.read(b, "$." + field));
        }
        // same timing class: while locked, neither checks a password at all
        verify(encoder, never()).matches(anyString(), anyString());
    }

    @Test
    void aSuccessfulSignInResetsTheCount() throws Exception {
        String email = email("reset");
        signedUp(email);
        for (int i = 0; i < 4; i++) {
            login(email, "wrong-password");
        }
        assertThat(login(email, "correct-horse-battery").getResponse().getStatus()).isEqualTo(200);
        for (int i = 0; i < 4; i++) {
            assertThat(login(email, "wrong-password").getResponse().getStatus()).isEqualTo(401);
        }
    }

    @Test
    void failedAdminSignInsAreLoggedWithoutTheEmail(CapturedOutput output) throws Exception {
        assertThat(login("Admin@Surge.local", "not-the-admin-password").getResponse().getStatus()).isEqualTo(401);
        assertThat(output.getOut()).contains("Failed sign-in for the admin account")
                .doesNotContain("Admin@Surge.local").doesNotContain("not-the-admin-password");
        assertThat(output.getOut().lines().filter(l -> l.contains("Failed sign-in for the admin account")))
                .allSatisfy(l -> assertThat(l).contains("WARN").doesNotContain("admin@surge.local"));
        // clear the count for the other tests that sign in as the admin
        assertThat(login("admin@surge.local", "surge-admin").getResponse().getStatus()).isEqualTo(200);
    }

    // ---- password policy ----------------------------------------------------------------------------------------

    @Test
    void commonPasswordsAndPasswordsContainingTheEmailAre400() throws Exception {
        // a refused signup creates nothing, so the fixed address is reusable
        String email = "asharani@example.com";
        for (String password : List.of("password1", "Password123", "AshaRani-2026")) {
            MvcResult res = signup(email, password);
            assertThat(res.getResponse().getStatus()).as(password).isEqualTo(400);
            String body = res.getResponse().getContentAsString();
            assertThat((String) JsonPath.read(body, "$.type")).isEqualTo(PROBLEM + "validation-failed");
            assertThat((String) JsonPath.read(body, "$.errors.password")).isNotBlank();
            assertThat(body).doesNotContain(password);
        }
        MvcResult localPart = signup("Asha.Rani@example.com", "my-ASHA.RANI-pass");
        assertThat(localPart.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(localPart.getResponse().getContentAsString(), "$.errors.password"))
                .contains("before the @");
        // nothing was created
        assertThat(login("asha.rani@example.com", "my-ASHA.RANI-pass").getResponse().getStatus()).isEqualTo(401);
        // a two-letter local part is not checked
        assertThat(signup("ab-" + UUID.randomUUID().toString().substring(0, 4) + "@example.com",
                "correct-horse-battery").getResponse().getStatus()).isEqualTo(201);
    }

    // ---- email verification ------------------------------------------------------------------------------------

    @Test
    void signupSendsACodeThatVerifiesTheEmailOnce() throws Exception {
        String email = email("verify");
        MvcResult res = signup(email, "correct-horse-battery");
        String body = res.getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.user.id");
        assertThat((Boolean) JsonPath.read(body, "$.user.emailVerified")).isFalse();
        assertThat(ev(body)).isFalse();

        JsonNode mail = mailpit.latestTo(email, 1).orElseThrow();
        assertThat(mail.get("Subject").asString()).isEqualTo("Your Surge verification code");
        String text = mail.get("Text").asString();
        assertThat(text).contains("expires in 15 minutes").contains("Spam").contains("Gmail app").contains("☰");
        String html = mail.get("HTML").asString();
        assertThat(html).contains("font-size:36px").contains("expires in 15 minutes").contains("☰");
        String code = codeFor(email, 1);
        assertThat(html).contains(code);

        MvcResult verified = submitCode(Identity.user(id), code);
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        String fresh = verified.getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(fresh, "$.user.emailVerified")).isTrue();
        assertThat(ev(fresh)).isTrue();

        // single use, and the account now says so everywhere
        mvc.perform(post("/api/auth/verify").with(Identity.user(id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "already-verified"));
        mvc.perform(get("/api/auth/me").with(Identity.user(id))).andExpect(jsonPath("$.emailVerified").value(true));
        assertThat(ev(login(email, "correct-horse-battery").getResponse().getContentAsString())).isTrue();
        mvc.perform(post("/api/auth/verify/resend").with(Identity.user(id))).andExpect(status().isConflict());
    }

    @Test
    void fiveWrongCodesLockTheCode() throws Exception {
        String email = email("lock");
        String id = signedUp(email);
        String code = codeFor(email, 1);
        for (int left = 4; left >= 1; left--) {
            MvcResult r = submitCode(Identity.user(id), wrong(code));
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.type"))
                    .isEqualTo(PROBLEM + "verification-code-invalid");
            assertThat((Integer) JsonPath.read(r.getResponse().getContentAsString(), "$.attemptsLeft")).isEqualTo(left);
        }
        MvcResult fifth = submitCode(Identity.user(id), wrong(code));
        assertThat((String) JsonPath.read(fifth.getResponse().getContentAsString(), "$.type"))
                .isEqualTo(PROBLEM + "verification-code-locked");
        // now even the right code fails, and the account stays unverified
        MvcResult right = submitCode(Identity.user(id), code);
        assertThat(right.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(right.getResponse().getContentAsString(), "$.type"))
                .isEqualTo(PROBLEM + "verification-code-locked");
        mvc.perform(get("/api/auth/me").with(Identity.user(id))).andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void resendIsLimited() throws Exception {
        String email = email("resend");
        String id = signedUp(email);
        // the signup email counts: a new code only after 60 s
        mvc.perform(post("/api/auth/verify/resend").with(Identity.user(id)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value(PROBLEM + "resend-throttled"))
                .andExpect(header().exists("Retry-After"));
        assertThat(Integer.parseInt(mvc.perform(post("/api/auth/verify/resend").with(Identity.user(id)))
                .andReturn().getResponse().getHeader("Retry-After"))).isBetween(1, 60);
        assertThat(mailpit.messagesTo(email)).hasSize(1);
    }

    @Test
    void theCodeEndpointsAreForSignedInUsersOnly() throws Exception {
        mvc.perform(post("/api/auth/verify").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
        mvc.perform(post("/api/auth/verify/resend").with(Identity.service("svc:notification")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/verify/resend")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/verify").with(Identity.user("u-1"))).andExpect(status().isForbidden());
        // a malformed code is a validation error, not a guess
        mvc.perform(post("/api/auth/verify").with(Identity.user("u-1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"12345\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").exists());
        // load-test identities have no mailbox: always verified
        mvc.perform(post("/api/auth/verify/resend").with(Identity.user("lt-run-1"))).andExpect(status().isConflict());
    }

    @Test
    void adminAndLoadTestTokensAreVerified() throws Exception {
        assertThat(ev(login("admin@surge.local", "surge-admin").getResponse().getContentAsString())).isTrue();
        String minted = mvc.perform(post("/api/auth/admin/test-tokens").with(Identity.admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"prefix\":\"lt-ev\",\"count\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(minted, "$.tokens[0].token");
        assertThat(ev("{\"token\":\"" + token + "\"}")).isTrue();
    }
}
