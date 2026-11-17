package dev.surge.booking.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.booking.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SEC-25 (V6__binary_collation.sql): user ids and idempotency keys compare byte for byte, like the Redis keys built
 * from them. Under MySQL's default {@code utf8mb4_0900_ai_ci}, {@code lt-Abc-1} and {@code lt-abc-1} were the same user
 * in {@code findByIdAndUserId}, so admin-minted synthetic users could read each other's orders.
 */
@IntegrationTest
class BinaryCollationTest {

    private static final String UPPER = "lt-Abc-1";
    private static final String LOWER = "lt-abc-1";

    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM orders WHERE user_id IN (?, ?)", UPPER, LOWER);
        jdbc.update("DELETE FROM payment_in_doubt WHERE user_id IN (?, ?)", UPPER, LOWER);
    }

    private long insertOrder(String userId, String key) {
        jdbc.update("INSERT INTO orders (user_id, event_id, status, total_inr, idempotency_key, created_at)"
                + " VALUES (?, 1, 'CONFIRMED', 100, ?, NOW(6))", userId, key);
        return jdbc.queryForObject("SELECT id FROM orders WHERE user_id = ? AND idempotency_key = ?", Long.class,
                userId, key);
    }

    @Test
    void userIdsThatDifferOnlyInCaseAreDifferentUsers() {
        long upper = insertOrder(UPPER, "key-0001");
        // the unique (user_id, idempotency_key) no longer treats them as one user
        long lower = insertOrder(LOWER, "key-0001");

        assertThat(orders.findByIdAndUserId(upper, LOWER)).isEmpty();
        assertThat(orders.findByIdAndUserId(lower, UPPER)).isEmpty();
        assertThat(orders.findByIdAndUserId(upper, UPPER)).isPresent();
        assertThat(orders.findByIdAndUserId(lower, LOWER)).isPresent();
        assertThat(orders.findByUserIdAndIdempotencyKey(LOWER, "key-0001")).map(Order::getId).contains(lower);
        assertThat(orders.findByUserIdAndIdempotencyKey(UPPER, "key-0001")).map(Order::getId).contains(upper);
    }

    @Test
    void idempotencyKeysThatDifferOnlyInCaseAreDifferentKeysAndEachIsStillUnique() {
        long a = insertOrder(UPPER, "key-AAAA");
        long b = insertOrder(UPPER, "key-aaaa");

        assertThat(orders.findByUserIdAndIdempotencyKey(UPPER, "key-AAAA")).map(Order::getId).contains(a);
        assertThat(orders.findByUserIdAndIdempotencyKey(UPPER, "key-aaaa")).map(Order::getId).contains(b);
        assertThatThrownBy(() -> insertOrder(UPPER, "key-AAAA")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inDoubtPaymentsKeepCaseVariantsApart() {
        for (String user : List.of(UPPER, LOWER)) {
            assertThat(jdbc.update("INSERT IGNORE INTO payment_in_doubt (reference, user_id, idempotency_key, "
                    + "amount_inr, created_at) VALUES (?, ?, 'key-0001', 100, NOW(6))", user + ":key-0001", user))
                    .as(user).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_in_doubt WHERE user_id = ?", Long.class, LOWER))
                .isEqualTo(1);
    }

    @Test
    void theColumnsAreBinaryNotNullAndTheConstraintsSurvive() {
        List<Map<String, Object>> columns = jdbc.queryForList("SELECT TABLE_NAME, COLUMN_NAME, COLLATION_NAME, "
                + "IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND ((TABLE_NAME = 'orders' AND COLUMN_NAME IN ('user_id', 'idempotency_key')) OR (TABLE_NAME = "
                + "'payment_in_doubt' AND COLUMN_NAME IN ('user_id', 'idempotency_key', 'reference'))) "
                + "ORDER BY TABLE_NAME, COLUMN_NAME");
        assertThat(columns).hasSize(5).allSatisfy(c -> {
            assertThat(c.get("COLLATION_NAME")).as(c.toString()).isEqualTo("utf8mb4_bin");
            assertThat(c.get("IS_NULLABLE")).as(c.toString()).isEqualTo("NO");
        });
        assertThat(columns).extracting(c -> c.get("TABLE_NAME") + "." + c.get("COLUMN_NAME") + ":"
                + c.get("CHARACTER_MAXIMUM_LENGTH")).containsExactly("orders.idempotency_key:100",
                "orders.user_id:100", "payment_in_doubt.idempotency_key:64", "payment_in_doubt.reference:200",
                "payment_in_doubt.user_id:64");

        List<String> unique = jdbc.queryForList("SELECT CONCAT(TABLE_NAME, '.', INDEX_NAME, '.', SEQ_IN_INDEX, '.', "
                + "COLUMN_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0 "
                + "AND TABLE_NAME IN ('orders', 'payment_in_doubt') AND INDEX_NAME <> 'PRIMARY' "
                + "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX", String.class);
        assertThat(unique).containsExactly("orders.uq_orders_user_idem.1.user_id",
                "orders.uq_orders_user_idem.2.idempotency_key",
                "payment_in_doubt.uk_payment_in_doubt_reference.1.reference");
    }
}
