package dev.surge.booking.event;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByEventIdOrderByRowLabelAscNumberAsc(long eventId);

    long countByEventIdAndStatus(long eventId, SeatStatus status);

    /**
     * The event's SOLD seats, read with a shared lock (InnoDB, REPEATABLE READ) on the
     * {@code idx_seat_event_status} range: it waits for a checkout that has marked a seat SOLD but not committed yet,
     * and a checkout that marks one later waits for this transaction (the new index entry falls into the locked
     * gap). Call it inside a transaction; the locks last until it ends.
     */
    @Query(value = "SELECT COUNT(*) FROM seat WHERE event_id = :eventId AND status = 'SOLD' FOR SHARE",
            nativeQuery = true)
    long countSoldLocking(@Param("eventId") long eventId);
}
