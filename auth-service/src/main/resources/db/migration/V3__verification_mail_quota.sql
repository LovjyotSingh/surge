-- N1: at most 10 verification emails per address per 24 hours, counted by address (not by account), so replacing a
-- stale unverified account or signing up again does not reset the budget.
CREATE TABLE verification_mail_quota (
    email          VARCHAR(254) COLLATE utf8mb4_bin NOT NULL,
    day_started_at DATETIME(6)  NOT NULL,
    sends_in_day   INT          NOT NULL,
    PRIMARY KEY (email)
);
