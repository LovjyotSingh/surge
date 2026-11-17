-- S5 (SEC-13): accounts start unverified; the admin is marked verified by the seeder.
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- One verification code per account: only its BCrypt hash, single use, 15 minutes, 5 wrong attempts. The send
-- counters rate-limit "send a new code": 1 per 60 s, 5 per hour and 10 per 24 hours, signup email included.
CREATE TABLE email_verifications (
    user_id           BIGINT       NOT NULL,
    code_hash         VARCHAR(100) NOT NULL,
    expires_at        DATETIME(6)  NOT NULL,
    failed_attempts   INT          NOT NULL,
    last_sent_at      DATETIME(6)  NOT NULL,
    window_started_at DATETIME(6)  NOT NULL,
    sends_in_window   INT          NOT NULL,
    day_started_at    DATETIME(6)  NOT NULL,
    sends_in_day      INT          NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_email_verifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
