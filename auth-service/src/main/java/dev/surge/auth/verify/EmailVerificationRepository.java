package dev.surge.auth.verify;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    /** Row-locked (SELECT ... FOR UPDATE), so parallel guesses or resends for one account are counted one by one. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from EmailVerification v where v.userId = :userId")
    Optional<EmailVerification> lockByUserId(@Param("userId") long userId);
}
