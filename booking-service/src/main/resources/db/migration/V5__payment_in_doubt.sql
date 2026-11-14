-- Charges whose outcome is unknown (timeout / I/O error after sending, or a failed order lookup after paying).
-- PaymentReconciler asks payment-service what happened and refunds orphans.
CREATE TABLE payment_in_doubt (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    reference       VARCHAR(200)  NOT NULL,
    user_id         VARCHAR(64)   NOT NULL,
    idempotency_key VARCHAR(64)   NOT NULL,
    amount_inr      DECIMAL(12,2) NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_in_doubt_reference (reference)
);
