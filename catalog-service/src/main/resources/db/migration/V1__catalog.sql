CREATE TABLE venue (
    id         BIGINT        NOT NULL,
    name       VARCHAR(200)  NOT NULL,
    city       VARCHAR(100)  NOT NULL,
    zone_id    VARCHAR(50)   NOT NULL,
    latitude   DECIMAL(9, 6) NOT NULL,
    longitude  DECIMAL(9, 6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE event (
    id                    BIGINT         NOT NULL AUTO_INCREMENT,
    title                 VARCHAR(200)   NOT NULL,
    artist                VARCHAR(200)   NULL,
    category              VARCHAR(30)    NOT NULL,
    venue_id              BIGINT         NOT NULL,
    starts_at             DATETIME(6)    NOT NULL,
    sale_opens_at         DATETIME(6)    NOT NULL,
    description           VARCHAR(500)   NULL,
    layout_json           JSON           NOT NULL,
    min_price_inr         DECIMAL(12, 2) NOT NULL,
    max_price_inr         DECIMAL(12, 2) NOT NULL,
    total_seats           INT            NOT NULL,
    seats_left            INT            NOT NULL,
    scheduled_published   BOOLEAN        NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT fk_event_venue FOREIGN KEY (venue_id) REFERENCES venue (id),
    INDEX idx_event_starts_at (starts_at, id),
    INDEX idx_event_unpublished (scheduled_published, id)
);

-- Filled by the orders.v1 consumer (task 10).
CREATE TABLE sales_daily (
    event_id      BIGINT         NOT NULL,
    day           DATE           NOT NULL,
    tickets_sold  INT            NOT NULL DEFAULT 0,
    revenue_inr   DECIMAL(14, 2) NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, day),
    CONSTRAINT fk_sales_daily_event FOREIGN KEY (event_id) REFERENCES event (id)
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

CREATE TABLE processed_message (
    message_id    CHAR(36)    NOT NULL,
    consumer      VARCHAR(64) NOT NULL,
    processed_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (message_id)
);
