package dev.surge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import dev.surge.auth.support.Identity;
import dev.surge.auth.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class TestTokenControllerTest {

    @Autowired
    MockMvc mvc;

    /** As signed-in user u-mint with exactly {@code roles} (comma separated; null = no roles at all). */
    MockHttpServletRequestBuilder mint(String roles, String json) {
        String[] list = roles == null ? new String[0] : roles.split(",");
        return post("/api/auth/admin/test-tokens").contentType(MediaType.APPLICATION_JSON).content(json)
                .with(Identity.userWithRoles("u-mint", list));
    }

    @Test
    void adminMintsValidTokensForSyntheticUsers() throws Exception {
        String json = mvc.perform(mint("USER,ADMIN", "{\"prefix\":\"lt-run1\",\"count\":50}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, String>> tokens = JsonPath.read(json, "$.tokens");
        assertThat(tokens).hasSize(50);
        assertThat(tokens.get(0)).containsEntry("userId", "lt-run1-0");
        assertThat(tokens.get(49)).containsEntry("userId", "lt-run1-49");

        String jwks = mvc.perform(get("/api/auth/.well-known/jwks.json")).andReturn().getResponse()
                .getContentAsString();
        RSAKey key = (RSAKey) JWKSet.parse(jwks).getKeys().get(0);
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        for (Map<String, String> t : tokens) {
            Jwt jwt = decoder.decode(t.get("token"));
            assertThat(jwt.getSubject()).isEqualTo(t.get("userId"));
            assertThat(jwt.<String>getClaim("email")).isEqualTo(t.get("userId") + "@loadtest.local");
            assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("USER");
            assertThat(jwt.getClaimAsString("iss")).isEqualTo("surge-auth");
            assertThat(jwt.<Boolean>getClaim("lt")).isTrue();
            assertThat(jwt.getAudience()).containsExactly("surge-api");
            assertThat(jwt.getId()).isNotBlank();
            assertThat(java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
                    .isEqualTo(java.time.Duration.ofMinutes(15));
        }
    }

    @Test
    void userRoleOrNoRoleIsForbidden() throws Exception {
        mvc.perform(mint("USER", "{\"prefix\":\"lt-run1\",\"count\":1}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/forbidden"));
        mvc.perform(mint(null, "{\"prefix\":\"lt-run1\",\"count\":1}")).andExpect(status().isForbidden());
    }

    @Test
    void roleIsCheckedBeforeTheBody() throws Exception {
        mvc.perform(mint("USER", "{\"count\":0}")).andExpect(status().isForbidden());
        mvc.perform(mint(null, "not json")).andExpect(status().isForbidden());
    }

    @Test
    void badInputIs400() throws Exception {
        mvc.perform(mint("ADMIN", "{\"prefix\":\"evil\",\"count\":1}")).andExpect(status().isBadRequest());
        mvc.perform(mint("ADMIN", "{\"count\":1}")).andExpect(status().isBadRequest());
        mvc.perform(mint("ADMIN", "not json")).andExpect(status().isBadRequest());
        mvc.perform(mint("ADMIN", "{\"prefix\":\"lt-" + "a".repeat(17) + "\",\"count\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(mint("ADMIN", "{\"prefix\":\"lt-ok\",\"count\":0}")).andExpect(status().isBadRequest());
        mvc.perform(mint("ADMIN", "{\"prefix\":\"lt-ok\",\"count\":20001}")).andExpect(status().isBadRequest());
    }

    /**
     * Guards against a slow mint (for example signing with a freshly parsed key per token), measured in CPU time of
     * the calling thread and not in wall-clock time. MockMvc runs the controller on this thread and the controller is
     * single-threaded, so the thread's CPU time is the work of the mint itself. Wall-clock time also counts every
     * moment this thread was not scheduled, so under CPU contention (a parallel build, a game) it fails for reasons
     * that have nothing to do with the code. The budget is 120 s of CPU (6 ms per token). An uncontended run needs
     * about 28 s (1.4 ms per token), so the budget is four times that: it absorbs slower CPUs and the cache effects of
     * a busy machine, while a real regression of the kind above costs far more. A very generous wall-clock cap
     * (10 minutes) is kept only to catch a hang.
     */
    @Test
    void mintsTwentyThousandInReasonableTime() throws Exception {
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(threads.isCurrentThreadCpuTimeSupported(),
                "the JVM cannot measure per-thread CPU time");
        long wallStart = System.nanoTime();
        long cpuStart = threads.getCurrentThreadCpuTime();
        String json = mvc.perform(mint("ADMIN", "{\"prefix\":\"lt-big\",\"count\":20000}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long cpuSeconds = (threads.getCurrentThreadCpuTime() - cpuStart) / 1_000_000_000;
        long wallSeconds = (System.nanoTime() - wallStart) / 1_000_000_000;
        assertThat((List<?>) JsonPath.read(json, "$.tokens")).hasSize(20000);
        assertThat(cpuSeconds).as("CPU seconds on the request thread (wall clock was %d s)", wallSeconds)
                .isLessThan(120);
        assertThat(wallSeconds).as("wall-clock seconds: only a hang should reach this").isLessThan(600);
    }

    /** SEC-19: a mint writes one ADMIN_ACTION line with the prefix and count only, never a token. */
    @Test
    void aMintWritesOneAuditLineWithoutTokens(CapturedOutput output) throws Exception {
        String json = mvc.perform(mint("USER,ADMIN", "{\"prefix\":\"lt-audit\",\"count\":3}")
                        .header("X-Request-Id", "rid-mint")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // refused by validation: no action, no line
        mvc.perform(mint("ADMIN", "{\"prefix\":\"nope\",\"count\":3}")).andExpect(status().isBadRequest());

        assertThat(output.getAll().lines().filter(l -> l.contains("ADMIN_ACTION"))
                .map(l -> l.substring(l.indexOf("ADMIN_ACTION"))).toList()).containsExactly(
                "ADMIN_ACTION actor=u-mint action=auth.test-tokens.mint target=prefix:lt-audit,count:3 "
                        + "requestId=rid-mint");
        List<String> tokens = JsonPath.read(json, "$.tokens[*].token");
        assertThat(tokens).hasSize(3).allSatisfy(token -> {
            assertThat(output.getAll()).doesNotContain(token);
            assertThat(output.getAll()).doesNotContain(token.substring(token.lastIndexOf('.') + 1));
        });
    }
}
