package dev.surge.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.booking.support.Identity;
import dev.surge.booking.support.IntegrationTest;
import dev.surge.booking.support.TestData;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The scheduled run is pushed to PT1H by @IntegrationTest; tests call {@code reconcile()} directly. */
@IntegrationTest
class PaymentReconcilerTest {

    @Autowired PaymentReconciler reconciler;
    @Autowired PaymentGateway gateway;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired TestData testData;

    @BeforeEach
    void reset() {
        testData.resetEvent(1L);
        jdbc.update("DELETE FROM payment_in_doubt");
        jdbc.update("DELETE FROM outbox");
    }

    /** Inserts an in-doubt record; an old one is past the hold ttl (5 min) + 1 min and so eligible. */
    private void inDoubt(String user, String key, boolean old) {
        LocalDateTime createdAt = LocalDateTime.ofInstant(Instant.now().minusSeconds(old ? 3600 : 5), ZoneOffset.UTC);
        jdbc.update("INSERT INTO payment_in_doubt (reference, user_id, idempotency_key, amount_inr, created_at)"
                + " VALUES (?, ?, ?, ?, ?)", user + ":" + key, user, key, new BigDecimal("4999.00"), createdAt);
    }

    private long rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_in_doubt", Long.class);
    }

    @Test
    void refundsAnApprovedPaymentThatHasNoOrder() {
        inDoubt("u1", "orphan", true);
        doReturn(Optional.of(new PaymentStatus(true, "pay_orphan", null, false))).when(gateway).status("u1:orphan");

        assertThat(reconciler.reconcile()).isEqualTo(1);

        verify(gateway).refund("pay_orphan");
        assertThat(rows()).isZero();
    }

    @Test
    void keepsAPaymentThatHasAnOrder() throws Exception {
        String holdBody = mvc.perform(post("/api/bookings/holds").with(Identity.user("u1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatIds\":[1]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String holdId = JsonPath.read(holdBody, "$.holdId");
        mvc.perform(post("/api/bookings/orders").with(Identity.user("u1")).header("Idempotency-Key", "key-paid")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"holdId\":\"" + holdId + "\"}"))
                .andExpect(status().isCreated());
        Mockito.clearInvocations(gateway);
        inDoubt("u1", "key-paid", true);

        assertThat(reconciler.reconcile()).isEqualTo(1);

        verify(gateway, never()).refund(any());
        assertThat(rows()).isZero();
    }

    @Test
    void deletesDeclinedRefundedAndUnknownRecordsWithoutRefunding() {
        inDoubt("u1", "declined", true);
        inDoubt("u1", "refunded", true);
        inDoubt("u1", "never-seen", true);
        doReturn(Optional.of(new PaymentStatus(false, "pay_d", "card_declined", false))).when(gateway)
                .status("u1:declined");
        doReturn(Optional.of(new PaymentStatus(true, "pay_r", null, true))).when(gateway).status("u1:refunded");
        doReturn(Optional.empty()).when(gateway).status("u1:never-seen");

        assertThat(reconciler.reconcile()).isEqualTo(3);

        verify(gateway, never()).refund(any());
        assertThat(rows()).isZero();
    }

    @Test
    void ignoresRecordsYoungerThanTheHoldTtl() {
        inDoubt("u1", "fresh", false);

        assertThat(reconciler.reconcile()).isZero();

        verify(gateway, never()).status(any());
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void keepsTheRecordWhenTheLookupFailsAndRetriesLater() {
        inDoubt("u1", "flaky", true);
        doThrow(new PaymentUnavailableException("circuit open", null)).when(gateway).status(any());

        assertThat(reconciler.reconcile()).isZero();
        assertThat(rows()).isEqualTo(1);
        verify(gateway, never()).refund(any());

        Mockito.reset(gateway);
        doReturn(Optional.of(new PaymentStatus(true, "pay_flaky", null, false))).when(gateway).status("u1:flaky");
        assertThat(reconciler.reconcile()).isEqualTo(1);
        verify(gateway).refund("pay_flaky");
        assertThat(rows()).isZero();
    }

    @Test
    void keepsTheRecordWhenTheRefundFails() {
        inDoubt("u1", "norefund", true);
        doReturn(Optional.of(new PaymentStatus(true, "pay_x", null, false))).when(gateway).status("u1:norefund");
        doThrow(new PaymentUnavailableException("down", null)).when(gateway).refund("pay_x");

        assertThat(reconciler.reconcile()).isZero();

        assertThat(rows()).isEqualTo(1);
    }
}
