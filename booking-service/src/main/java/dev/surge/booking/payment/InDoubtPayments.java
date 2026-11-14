package dev.surge.booking.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Writes the {@code payment_in_doubt} rows that {@link PaymentReconciler} later resolves. */
@Component
public class InDoubtPayments {

    private static final Logger log = LoggerFactory.getLogger(InDoubtPayments.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public InDoubtPayments(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Records a charge of unknown outcome. Duplicates are ignored; a DB failure is logged, never thrown. */
    public void record(String userId, String idempotencyKey, BigDecimal amountInr) {
        String reference = userId + ":" + idempotencyKey;
        try {
            jdbc.update("INSERT IGNORE INTO payment_in_doubt (reference, user_id, idempotency_key, amount_inr, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)", reference, userId, idempotencyKey, amountInr,
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            log.warn("Payment in doubt recorded for reconciliation: reference={}", reference);
        } catch (RuntimeException e) {
            log.error("PAYMENT IN DOUBT AND NOT RECORDED, manual action needed: reference={}", reference, e);
        }
    }
}
