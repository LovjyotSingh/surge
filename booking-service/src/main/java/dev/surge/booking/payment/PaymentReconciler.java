package dev.surge.booking.payment;

import dev.surge.booking.order.OrderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves charges whose outcome booking never learned. A record is only looked at once it is older than
 * {@code surge.hold.ttl + 1 min}: by then the user's own retry (same Idempotency-Key, same reference) has either
 * produced an order or can no longer do so. Each record is handled alone, in its own transaction, under
 * {@code FOR UPDATE SKIP LOCKED}, so several booking instances never refund the same payment twice.
 */
@Component
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);
    static final int BATCH = 50;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PaymentGateway payments;
    private final OrderRepository orders;
    private final Clock clock;
    private final Duration minAge;

    public PaymentReconciler(JdbcTemplate jdbc, TransactionTemplate tx, PaymentGateway payments,
                             OrderRepository orders, Clock clock, @Value("${surge.hold.ttl:PT5M}") Duration holdTtl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.payments = payments;
        this.orders = orders;
        this.clock = clock;
        this.minAge = holdTtl.plusMinutes(1);
    }

    /** Both delays use the same property so the first run never fires at context start-up when it is large. */
    @Scheduled(fixedDelayString = "${surge.payment.reconcile-interval:PT30S}",
            initialDelayString = "${surge.payment.reconcile-interval:PT30S}")
    public void scheduledReconcile() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.error("Payment reconciliation failed", e);
        }
    }

    /** Processes up to 50 old records; returns how many were resolved (deleted). */
    public int reconcile() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant().minus(minAge), ZoneOffset.UTC);
        long lastId = 0;
        int resolved = 0;
        for (int i = 0; i < BATCH; i++) {
            long after = lastId;
            Outcome outcome = tx.execute(status -> handleNext(after, cutoff));
            if (outcome == null || outcome.id() < 0) {
                break; // nothing (more) to do
            }
            lastId = outcome.id();
            if (outcome.resolved()) {
                resolved++;
            }
        }
        return resolved;
    }

    private record Outcome(long id, boolean resolved) {
    }

    /** Locks and handles the next old record after {@code afterId}; kept (not resolved) when the provider is unavailable. */
    private Outcome handleNext(long afterId, LocalDateTime cutoff) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, reference, user_id, idempotency_key FROM payment_in_doubt WHERE id > ? AND created_at < ?"
                        + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", afterId, cutoff);
        if (rows.isEmpty()) {
            return new Outcome(-1, false);
        }
        Map<String, Object> row = rows.get(0);
        long id = ((Number) row.get("id")).longValue();
        String reference = (String) row.get("reference");
        String userId = (String) row.get("user_id");
        String key = (String) row.get("idempotency_key");

        Optional<PaymentStatus> status;
        try {
            status = payments.status(reference);
        } catch (RuntimeException e) {
            log.warn("Payment lookup unavailable, will retry: reference={}: {}", reference, e.getMessage());
            return new Outcome(id, false);
        }
        if (status.isPresent() && status.get().approved() && !status.get().refunded()
                && orders.findByUserIdAndIdempotencyKey(userId, key).isEmpty()) {
            try {
                payments.refund(status.get().paymentId());
                log.warn("Refunded an orphaned payment: reference={} paymentId={}", reference, status.get().paymentId());
            } catch (RuntimeException e) {
                log.error("REFUND FAILED, will retry: reference={} paymentId={}", reference,
                        status.get().paymentId(), e);
                return new Outcome(id, false);
            }
        }
        jdbc.update("DELETE FROM payment_in_doubt WHERE id = ?", id);
        return new Outcome(id, true);
    }
}
