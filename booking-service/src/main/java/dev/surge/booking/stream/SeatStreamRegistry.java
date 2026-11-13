package dev.surge.booking.stream;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Tracks the open SSE connections per event and fans seat state changes out to them. */
@Component
public class SeatStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(SeatStreamRegistry.class);
    private static final long TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final JsonMapper json;

    public SeatStreamRegistry(JsonMapper json) {
        this.json = json;
    }

    public SseEmitter subscribe(long eventId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        Runnable remove = () -> remove(eventId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
        emitters.computeIfAbsent(eventId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | RuntimeException e) {
            remove.run();
        }
        return emitter;
    }

    public void broadcast(SeatStateChange change) {
        List<SseEmitter> targets = emitters.get(change.eventId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = json.writeValueAsString(change);
        } catch (JacksonException e) {
            log.warn("Could not serialise seat change {}", change, e);
            return;
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name("seats").data(payload));
            } catch (IOException | RuntimeException e) {
                remove(change.eventId(), emitter);
            }
        }
    }

    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        emitters.forEach((eventId, targets) -> {
            for (SseEmitter emitter : targets) {
                try {
                    emitter.send(SseEmitter.event().comment("hb"));
                } catch (IOException | RuntimeException e) {
                    remove(eventId, emitter);
                }
            }
        });
    }

    /** Completes and drops every open stream for an event (used for clean shutdown in tests). */
    public void completeAll(long eventId) {
        List<SseEmitter> targets = emitters.remove(eventId);
        if (targets != null) {
            targets.forEach(SseEmitter::complete);
        }
    }

    int subscriberCount(long eventId) {
        List<SseEmitter> targets = emitters.get(eventId);
        return targets == null ? 0 : targets.size();
    }

    private void remove(long eventId, SseEmitter emitter) {
        emitters.computeIfPresent(eventId, (id, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
