package dev.surge.catalog.event;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EventRepository extends JpaRepository<Event, Long> {

    @EntityGraph(attributePaths = "venue")
    Page<Event> findAllBy(Pageable pageable);

    @EntityGraph(attributePaths = "venue")
    Optional<Event> findWithVenueById(Long id);

    /** Locks the rows, so two publishers running at once (startup and an import) never publish the same event twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e join fetch e.venue where e.scheduledPublished = false order by e.id")
    List<Event> lockUnpublished();
}
