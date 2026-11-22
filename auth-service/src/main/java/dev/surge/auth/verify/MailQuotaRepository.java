package dev.surge.auth.verify;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MailQuotaRepository extends JpaRepository<MailQuota, String> {

    /** Creates the address's row (an already-expired window) unless it exists; never fails on a race. */
    @Modifying
    @Query(value = "INSERT IGNORE INTO verification_mail_quota (email, day_started_at, sends_in_day) "
            + "VALUES (:email, '1970-01-01 00:00:00', 0)", nativeQuery = true)
    void createIfMissing(@Param("email") String email);

    /** Row-locked, so two sends to one address are counted one by one. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from MailQuota q where q.email = :email")
    Optional<MailQuota> lockByEmail(@Param("email") String email);
}
