package dev.surge.notification.audit;

import dev.surge.notification.ticket.ProcessedMessageRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims the message id for consumer {@code audit} and appends its line, in one transaction: the claim comes first,
 * and when it is ignored (a redelivery) nothing is appended. A failed append rolls the claim back so the retry starts
 * clean. The one gap: if the commit itself fails after the line was appended, the redelivery appends the line
 * again, so a duplicate line is possible but a lost one is not.
 */
@Service
public class AuditRecorder {

    static final String CONSUMER = "audit";

    private final ProcessedMessageRepository processed;
    private final AuditLogWriter writer;
    private final Clock clock;

    public AuditRecorder(ProcessedMessageRepository processed, AuditLogWriter writer, Clock clock) {
        this.processed = processed;
        this.writer = writer;
        this.clock = clock;
    }

    /** @return true when the line was written, false when this message id was already audited */
    @Transactional
    public boolean record(String messageId, String type, String payloadJson) {
        if (processed.claim(messageId, CONSUMER, Instant.now(clock)) == 0) {
            return false;
        }
        writer.append(type, payloadJson);
        return true;
    }
}
