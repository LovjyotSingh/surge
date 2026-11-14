CREATE TABLE ticket (
    order_id   BIGINT       NOT NULL PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL,
    event_id   BIGINT       NOT NULL,
    s3_key     VARCHAR(200) NOT NULL,
    emailed_at DATETIME(6)  NULL,
    created_at DATETIME(6)  NOT NULL
);

-- Idempotent consumers: one row per message that has already been handled.
CREATE TABLE processed_message (
    message_id   CHAR(36)    NOT NULL PRIMARY KEY,
    consumer     VARCHAR(64) NOT NULL,
    processed_at DATETIME(6) NOT NULL
);
