package dev.surge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import dev.surge.auth.support.Identity;
import dev.surge.auth.support.IntegrationTest;
import dev.surge.auth.support.MailpitContainer;
import dev.surge.auth.user.Role;
import dev.surge.auth.user.User;
import dev.surge.auth.user.UserRepository;
import dev.surge.auth.verify.EmailVerification;
import dev.surge.auth.verify.EmailVerificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** N1: signup as a spam or lock-out tool: the display name, stale unverified accounts and the per-address budget. */
@IntegrationTest
class SignupAbuseTest {

    private static final String PROBLEM = "https://surge.dev/problems/";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    EmailVerificationRepository codes;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    MailpitContainer mailpit;

    private static String email(String tag) {
        return tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private MvcResult signup(String email, String password, String name) throws Exception {
        return mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"displayName\":\"" + name
                        + "\"}")).andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    /** An account as it would be after {@code age}: unverified or verified, with its code. */
    private long account(String email, String password, Duration age, boolean verified) {
        Instant created = Instant.now().minus(age);
        User user = new User(email, encoder.encode(password), "Old Owner", Role.USER, created);
        user.setEmailVerified(verified);
        long id = users.saveAndFlush(user).getId();
        if (!verified) {
            codes.saveAndFlush(EmailVerification.first(id, encoder.encode("123456"), created));
        }
        return id;
    }

    @Test
    void aBadDisplayNameIs400WithAClearDetailAndNothingIsCreatedOrEmailed() throws Exception {
        for (String name : new String[] {"http://evil.example/pay", "Visit evil.com", "a".repeat(61), "<b>x</b>"}) {
            String email = email("badname");
            mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse\",\"displayName\":\""
                                    + name + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "validation-failed"))
                    .andExpect(jsonPath("$.errors.displayName").value(org.hamcrest.Matchers.containsString("letters")));
            assertThat(users.findByEmail(email)).isEmpty();
            assertThat(mailpit.messagesTo(email)).isEmpty();
        }
    }

    @Test
    void theCodeEmailNeverContainsTheDisplayName() throws Exception {
        String email = email("noname");
        assertThat(signup(email, "correct-horse", "Zebra Quux").getResponse().getStatus()).isEqualTo(201);
        JsonNode message = mailpit.latestTo(email, 1).orElseThrow(() -> new AssertionError("no email"));
        assertThat(message.get("Text").asString()).contains("Your Surge verification code is").doesNotContain("Zebra")
                .doesNotContain("Quux");
        assertThat(message.get("HTML").asString()).doesNotContain("Zebra").doesNotContain("Quux");
    }

    @Test
    void anUnverifiedAccountOlderThan24HoursIsReplacedAndItsCodeGoesWithIt() throws Exception {
        String email = email("stale");
        long old = account(email, "old-owner-password", Duration.ofHours(25), false);
        assertThat(codes.findById(old)).isPresent();

        MvcResult res = signup(email, "new-owner-password", "New Owner");
        assertThat(res.getResponse().getStatus()).isEqualTo(201);

        assertThat(users.findById(old)).isEmpty();
        assertThat(codes.findById(old)).isEmpty();
        assertThat(users.findByEmail(email)).hasValueSatisfying(u -> {
            assertThat(u.getId()).isNotEqualTo(old);
            assertThat(u.getDisplayName()).isEqualTo("New Owner");
            assertThat(u.isEmailVerified()).isFalse();
        });
        assertThat(login(email, "new-owner-password").getResponse().getStatus()).isEqualTo(200);
        assertThat(login(email, "old-owner-password").getResponse().getStatus()).isEqualTo(401);
        assertThat(mailpit.latestTo(email, 1)).isPresent();
    }

    @Test
    void anUnverifiedAccountYoungerThan24HoursKeepsItsAddress() throws Exception {
        String email = email("young");
        long old = account(email, "old-owner-password", Duration.ofHours(23), false);

        mvc.perform(post("/api/auth/signup").with(Identity.anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"new-owner-password\",\"displayName\":\"Eve\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value(PROBLEM + "email-taken"));
        assertThat(users.findById(old)).isPresent();
        assertThat(codes.findById(old)).isPresent();
    }

    /** The take-over check: however old, a verified account is never replaced. */
    @Test
    void aVerifiedAccountIsNeverReplaced() throws Exception {
        String email = email("verified");
        long old = account(email, "old-owner-password", Duration.ofDays(400), true);

        assertThat(signup(email, "attacker-password-1", "Mallory").getResponse().getStatus()).isEqualTo(409);

        assertThat(users.findById(old)).hasValueSatisfying(u -> assertThat(u.getDisplayName()).isEqualTo("Old Owner"));
        assertThat(login(email, "old-owner-password").getResponse().getStatus()).isEqualTo(200);
        assertThat(login(email, "attacker-password-1").getResponse().getStatus()).isEqualTo(401);
        assertThat(mailpit.messagesTo(email)).isEmpty();
        // the admin is verified too, and its address is refused outright
        assertThat(signup("admin@surge.local", "attacker-password-1", "Mallory").getResponse().getStatus())
                .isEqualTo(409);
    }

    /** The conditional delete is what keeps a verification that lands mid-signup from being deleted. */
    @Test
    void theDeleteOnlyMatchesAnUnverifiedUserOlderThanTheCutoff() {
        long verified = account(email("d1"), "pw-correct-horse", Duration.ofDays(2), true);
        long young = account(email("d2"), "pw-correct-horse", Duration.ofHours(1), false);
        long stale = account(email("d3"), "pw-correct-horse", Duration.ofDays(2), false);
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));

        assertThat(deleteInTx(verified, cutoff)).isZero();
        assertThat(deleteInTx(young, cutoff)).isZero();
        assertThat(deleteInTx(stale, cutoff)).isEqualTo(1);
        assertThat(users.findById(verified)).isPresent();
        assertThat(users.findById(young)).isPresent();
        assertThat(users.findById(stale)).isEmpty();
        assertThat(codes.findById(stale)).isEmpty();
    }

    @Autowired
    org.springframework.transaction.support.TransactionTemplate txTemplate;

    private int deleteInTx(long id, Instant cutoff) {
        Integer n = txTemplate.execute(s -> users.deleteStaleUnverified(id, cutoff));
        return n == null ? -1 : n;
    }

    /** One address, however many accounts claim it in turn, gets 10 emails a day. */
    @Test
    void anAddressGetsAtMostTenVerificationEmailsADayAcrossSignups() throws Exception {
        String email = email("cap");
        for (int i = 1; i <= 10; i++) {
            account(email, "old-owner-password", Duration.ofHours(30), false);
            assertThat(signup(email, "new-owner-password", "Ada").getResponse().getStatus()).as("signup %d", i)
                    .isEqualTo(201);
            users.findByEmail(email).ifPresent(u -> users.delete(u));
        }
        assertThat(mailpit.latestTo(email, 10)).isPresent();

        long stale = account(email, "old-owner-password", Duration.ofHours(30), false);
        MvcResult eleventh = signup(email, "new-owner-password", "Ada");
        assertThat(eleventh.getResponse().getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(eleventh.getResponse().getHeader("Retry-After"))).isPositive();
        assertThat(eleventh.getResponse().getContentAsString()).contains(PROBLEM + "resend-throttled");
        // a refused signup changes nothing: the old account is still there, and no 11th email went out
        assertThat(users.findById(stale)).isPresent();
        assertThat(mailpit.messagesTo(email)).hasSize(10);

        // a resend counts against the same budget
        mvc.perform(post("/api/auth/verify/resend").with(Identity.user("u-" + stale))).andExpect(status().isTooManyRequests());
    }
}
