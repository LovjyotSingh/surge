CREATE TABLE processed_message (
    message_id    CHAR(36)    NOT NULL,
    consumer      VARCHAR(64) NOT NULL,
    processed_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (message_id)
);
