package dev.surge.booking.order;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByIdAndUserId(long id, String userId);

    Optional<Order> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);
}
