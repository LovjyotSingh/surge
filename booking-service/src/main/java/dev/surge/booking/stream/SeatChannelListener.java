package dev.surge.booking.stream;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Receives every instance's seat publishes from Redis pub/sub and hands them to local SSE subscribers. */
@Component
public class SeatChannelListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(SeatChannelListener.class);

    private final SeatStreamRegistry registry;
    private final JsonMapper json;

    public SeatChannelListener(SeatStreamRegistry registry, JsonMapper json) {
        this.registry = registry;
        this.json = json;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            registry.broadcast(json.readValue(body, SeatStateChange.class));
        } catch (RuntimeException e) {
            log.warn("Ignoring malformed seat message", e);
        }
    }
}
