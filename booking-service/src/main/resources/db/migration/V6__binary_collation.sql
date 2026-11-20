-- SEC-25: user ids and idempotency keys compare byte for byte, like the Redis keys built from them. Under MySQL's
-- default utf8mb4_0900_ai_ci, 'lt-Abc-1' and 'lt-abc-1' were one user in findByIdAndUserId (synthetic users could read
-- each other's orders) and the unique (user_id, idempotency_key) treated keys that differ only in case as one.
-- utf8mb4_0900_bin, not utf8mb4_bin: it is NO PAD, so 'k' and 'k ' are different values too (utf8mb4_bin is PAD SPACE
-- and would still compare them equal).
-- payment_in_doubt.reference is 'userId:key', so it changes too (its unique key must not merge two users either).
-- MODIFY keeps each column's type, length and NOT NULL; MySQL rebuilds uq_orders_user_idem and
-- uk_payment_in_doubt_reference with the new collation (no existing rows can collide: binary is stricter).
-- Changing a collation rebuilds the table (ALGORITHM=COPY) and blocks writes to it while it runs. Fine for the
-- current small tables; on a large orders table, run it in a maintenance window (or with an online schema-change
-- tool).
ALTER TABLE orders
    MODIFY user_id         VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY idempotency_key VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;

ALTER TABLE payment_in_doubt
    MODIFY reference       VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY user_id         VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY idempotency_key VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
