package dev.surge.notification.ticket;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, ProcessedMessageId> {

    /**
     * Claims a message id for one consumer (the key is consumer + message id). {@code INSERT IGNORE} is atomic
     * and, unlike a failed plain insert, does not mark the surrounding transaction rollback-only. Returns 1 when
     * this call claimed it, 0 when it was already processed.
     * A concurrent claim of the same id waits on the row lock until the first transaction ends.
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO processed_message (message_id, consumer, processed_at) "
            + "VALUES (:messageId, :consumer, :processedAt)", nativeQuery = true)
    int claim(@Param("messageId") String messageId, @Param("consumer") String consumer,
              @Param("processedAt") Instant processedAt);
}
