package dev.surge.auth.verify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** How many verification emails an address got in its current 24-hour window; the key is the normalised email. */
@Entity
@Table(name = "verification_mail_quota")
class MailQuota {

    @Id
    @Column(name = "email", length = 254)
    private String email;
    @Column(name = "day_started_at", nullable = false)
    private Instant dayStartedAt;
    @Column(name = "sends_in_day", nullable = false)
    private int sendsInDay;

    protected MailQuota() {
    }

    Instant dayStartedAt() {
        return dayStartedAt;
    }

    int sendsInDay() {
        return sendsInDay;
    }

    void startDay(Instant now) {
        dayStartedAt = now;
        sendsInDay = 0;
    }

    void recordSend() {
        sendsInDay++;
    }
}
