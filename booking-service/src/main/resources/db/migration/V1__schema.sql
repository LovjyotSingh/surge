CREATE TABLE event_ref (
    id             BIGINT       NOT NULL,
    title          VARCHAR(200) NOT NULL,
    venue_name     VARCHAR(200) NOT NULL,
    sale_opens_at  DATETIME(6)  NOT NULL,
    zone_id        VARCHAR(50)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE seat (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    event_id   BIGINT      NOT NULL,
    section    VARCHAR(50) NOT NULL,
    row_label  VARCHAR(10) NOT NULL,
    number     INT         NOT NULL,
    tier       VARCHAR(20) NOT NULL,
    status     VARCHAR(20) NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_seat_position UNIQUE (event_id, row_label, number),
    CONSTRAINT fk_seat_event FOREIGN KEY (event_id) REFERENCES event_ref (id),
    INDEX idx_seat_event_status (event_id, status)
);

CREATE TABLE orders (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    user_id          VARCHAR(100)   NOT NULL,
    event_id         BIGINT         NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    total_inr        DECIMAL(12, 2) NOT NULL,
    idempotency_key  VARCHAR(100)   NOT NULL,
    created_at       DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_orders_user_idem UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_orders_event FOREIGN KEY (event_id) REFERENCES event_ref (id)
);

CREATE TABLE order_item (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    order_id   BIGINT         NOT NULL,
    seat_id    BIGINT         NOT NULL,
    price_inr  DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_order_item_seat UNIQUE (seat_id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_item_seat FOREIGN KEY (seat_id) REFERENCES seat (id)
);

CREATE TABLE outbox (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_id  VARCHAR(100) NOT NULL,
    type          VARCHAR(100) NOT NULL,
    destination   VARCHAR(200) NOT NULL,
    payload       JSON         NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    published_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_outbox_published_id (published_at, id)
);
