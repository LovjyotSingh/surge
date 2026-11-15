-- The same message id can now be claimed once per consumer (the Rabbit ticket consumer and the Kafka audit consumer
-- both see ids that originate in the same outbox).
ALTER TABLE processed_message
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (consumer, message_id);
