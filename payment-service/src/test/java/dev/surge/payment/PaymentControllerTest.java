package dev.surge.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class PaymentControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper json;
    @Autowired
    MeterRegistry meters;

    @AfterEach
    void resetChaos() throws Exception {
        setChaos(0, 0, 0, "ADMIN").andExpect(status().isNoContent());
    }

    private static String ref() {
        return "u-1:" + UUID.randomUUID();
    }

    private static String body(String ref, String userId, String amount) {
        return "{\"reference\":\"" + ref + "\",\"userId\":\"" + userId + "\",\"amountInr\":" + amount + "}";
    }

    private ResultActions charge(String ref, String userId, String amount) throws Exception {
        return mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                .content(body(ref, userId, amount)));
    }

    private ResultActions setChaos(double failure, int latency, double error, String roles) throws Exception {
        var req = put("/api/payments/admin/chaos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"failureRate\":" + failure + ",\"latencyMs\":" + latency + ",\"errorRate\":" + error + "}");
        if (roles != null) {
            req.header("X-User-Roles", roles);
        }
        return mvc.perform(req);
    }

    private String paymentId(ResultActions r) throws Exception {
        JsonNode n = json.readTree(r.andReturn().getResponse().getContentAsString());
        return n.get("paymentId").asString();
    }

    private double count(String name, String result) {
        var c = result == null ? meters.find(name).counter() : meters.find(name).tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void approvesAndIsIdempotentByReference() throws Exception {
        String ref = ref();
        ResultActions first = charge(ref, "u-1", "499.50").andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(true)).andExpect(jsonPath("$.declineReason").doesNotExist());
        String id = paymentId(first);
        assertThat(id).startsWith("pay_");
        double before = count("payments_total", "approved");
        charge(ref, "u-1", "499.50").andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value(id));
        assertThat(count("payments_total", "approved")).isEqualTo(before);
    }

    @Test
    void storedResultSurvivesChaosChange() throws Exception {
        String ref = ref();
        String id = paymentId(charge(ref, "u-1", "10"));
        setChaos(1, 0, 0, "ADMIN").andExpect(status().isNoContent());
        charge(ref, "u-1", "10").andExpect(jsonPath("$.approved").value(true))
                .andExpect(jsonPath("$.paymentId").value(id));
    }

    @Test
    void concurrentSameReferenceProducesOnePayment() throws Exception {
        String ref = ref();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                Callable<String> task = () -> paymentId(charge(ref, "u-1", "75.00").andExpect(status().isOk()));
                futures.add(pool.submit(task));
            }
            List<String> ids = new ArrayList<>();
            for (Future<String> f : futures) {
                ids.add(f.get());
            }
            assertThat(ids).hasSize(50).containsOnly(ids.getFirst());
        }
    }

    @Test
    void sameReferenceDifferentAmountOrUserIsConflict() throws Exception {
        String ref = ref();
        charge(ref, "u-1", "100").andExpect(status().isOk());
        charge(ref, "u-1", "100.01").andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/reference-conflict"));
        charge(ref, "u-2", "100").andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/reference-conflict"));
        charge(ref, "u-1", "100.00").andExpect(status().isOk());
    }

    @Test
    void refundIsIdempotentAndUnknownIs404() throws Exception {
        String id = paymentId(charge(ref(), "u-1", "20"));
        double before = count("refunds_total", null);
        mvc.perform(post("/api/payments/" + id + "/refund")).andExpect(status().isOk())
                .andExpect(jsonPath("$.refunded").value(true));
        mvc.perform(post("/api/payments/" + id + "/refund")).andExpect(status().isOk())
                .andExpect(jsonPath("$.refunded").value(true));
        assertThat(count("refunds_total", null)).isEqualTo(before + 1);
        mvc.perform(post("/api/payments/pay_nope/refund")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/payment-not-found"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void decliningChaosDeclinesAndDeclinedCannotBeRefunded() throws Exception {
        double before = count("payments_total", "declined");
        setChaos(1, 0, 0, "ADMIN").andExpect(status().isNoContent());
        String ref = ref();
        ResultActions r = charge(ref, "u-1", "30").andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.declineReason").value("card_declined"));
        String id = paymentId(r);
        assertThat(count("payments_total", "declined")).isEqualTo(before + 1);
        mvc.perform(post("/api/payments/" + id + "/refund")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/not-refundable"));
        // the decline is stored: a retry with chaos off still sees it
        setChaos(0, 0, 0, "ADMIN").andExpect(status().isNoContent());
        charge(ref, "u-1", "30").andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.paymentId").value(id));
    }

    @Test
    void errorChaosReturns500ThenRetrySucceeds() throws Exception {
        double before = count("payments_total", "error");
        setChaos(0, 0, 1, "ADMIN").andExpect(status().isNoContent());
        String ref = ref();
        charge(ref, "u-1", "40").andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/internal-error"))
                .andExpect(jsonPath("$.requestId").exists());
        assertThat(count("payments_total", "error")).isEqualTo(before + 1);
        setChaos(0, 0, 0, "ADMIN").andExpect(status().isNoContent());
        charge(ref, "u-1", "40").andExpect(status().isOk()).andExpect(jsonPath("$.approved").value(true));
    }

    @Test
    void latencyIsHonoured() throws Exception {
        setChaos(0, 300, 0, "ADMIN").andExpect(status().isNoContent());
        long start = System.nanoTime();
        charge(ref(), "u-1", "50").andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(300);
    }

    @Test
    void chaosNeedsAdmin() throws Exception {
        setChaos(0, 0, 0, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/forbidden"));
        setChaos(0, 0, 0, "USER").andExpect(status().isForbidden());
        setChaos(0, 0, 0, "SUPERADMIN").andExpect(status().isForbidden());
        mvc.perform(get("/api/payments/admin/chaos")).andExpect(status().isForbidden());
        setChaos(0.25, 120, 0.5, "USER, ADMIN").andExpect(status().isNoContent());
        mvc.perform(get("/api/payments/admin/chaos").header("X-User-Roles", "ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.failureRate").value(0.25)).andExpect(jsonPath("$.latencyMs").value(120))
                .andExpect(jsonPath("$.errorRate").value(0.5));
    }

    @Test
    void chaosValuesAreValidated() throws Exception {
        setChaos(1.5, 0, 0, "ADMIN").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"));
        setChaos(0, 10_001, 0, "ADMIN").andExpect(status().isBadRequest());
        setChaos(0, -1, 0, "ADMIN").andExpect(status().isBadRequest());
        setChaos(0, 0, -0.1, "ADMIN").andExpect(status().isBadRequest());
    }

    @Test
    void invalidChargesAre400() throws Exception {
        charge("", "u-1", "10").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/validation-failed"));
        charge("x".repeat(201), "u-1", "10").andExpect(status().isBadRequest());
        charge(ref(), "bad user!", "10").andExpect(status().isBadRequest());
        charge(ref(), "u-1", "0").andExpect(status().isBadRequest());
        charge(ref(), "u-1", "-5").andExpect(status().isBadRequest());
        charge(ref(), "u-1", "10000000.01").andExpect(status().isBadRequest());
        charge(ref(), "u-1", "1.234").andExpect(status().isBadRequest());
        charge("r".repeat(200), "u-1", "10000000").andExpect(status().isOk());
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/malformed-request"));
    }

    @Test
    void approvedCounterIncrements() throws Exception {
        double before = count("payments_total", "approved");
        charge(ref(), "u-1", "5").andExpect(status().isOk());
        assertThat(count("payments_total", "approved")).isEqualTo(before + 1);
    }
}
