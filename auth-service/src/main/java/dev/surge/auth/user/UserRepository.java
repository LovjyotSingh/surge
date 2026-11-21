package dev.surge.auth.user;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    java.util.List<User> findByRoleAndEmailNot(Role role, String email);

    /**
     * Deletes the account only if it is still a USER whose email is unverified and that was created before
     * {@code cutoff}, in one statement (its verification code goes with it, by the foreign key's cascade). Returns 0
     * when it is not: for instance, the owner verified it a moment ago. This one condition is what stops a signup
     * from ever replacing a verified account.
     */
    @Modifying
    @Query("delete from User u where u.id = :id and u.emailVerified = false and u.role = dev.surge.auth.user.Role.USER "
            + "and u.createdAt < :cutoff")
    int deleteStaleUnverified(@Param("id") long id, @Param("cutoff") Instant cutoff);
}
