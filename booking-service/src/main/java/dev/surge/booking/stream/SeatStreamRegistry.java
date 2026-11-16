package dev.surge.booking.stream;

import dev.surge.booking.error.StreamCapacityException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tracks the open SSE connections per event and fans seat state changes out to them.
 *
 * <p>SEC-09: streams are public and each holds a connection, so they are capped per event
 * ({@code surge.stream.max-subscribers-per-event}, default 5,000) and in total
 * ({@code surge.stream.max-subscribers-total}, default 15,000, leaving Tomcat's 20,000 connections room for holds and
 * checkout). Past a cap {@link #subscribe} throws {@link StreamCapacityException} (503 {@code stream-capacity}). An
 * emitter lives at most 10 minutes; EventSource reconnects by itself after a normal close.
 */
@Component
public class SeatStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(SeatStreamRegistry.class);
    private static final long TIMEOUT_MILLIS = Duration.ofMinutes(10).toMillis();

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final JsonMapper json;
    private final int maxPerEvent;
    private final int maxTotal;

    public SeatStreamRegistry(JsonMapper json,
            @Value("${surge.stream.max-subscribers-per-event:5000}") int maxPerEvent,
            @Value("${surge.stream.max-subscribers-total:15000}") int maxTotal) {
        if (maxPerEvent < 1 || maxTotal < 1) {
            throw new IllegalArgumentException("surge.stream.max-subscribers-* must be at least 1");
        }
        this.json = json;
        this.maxPerEvent = maxPerEvent;
        this.maxTotal = maxTotal;
    }

    /** A new stream for the event, or {@link StreamCapacityException} when the event or the service is full. */
    public SseEmitter subscribe(long eventId) {
        if (total.incrementAndGet() > maxTotal) {
            total.decrementAndGet();
            throw new StreamCapacityException("Too many live seat streams are open; try again later");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        boolean[] added = {false};
        emitters.compute(eventId, (id, list) -> {
            CopyOnWriteArrayList<SseEmitter> targets = list == null ? new CopyOnWriteArrayList<>() : list;
            if (targets.size() < maxPerEvent) {
                added[0] = targets.add(emitter);
            }
            return targets.isEmpty() ? null : targets;
        });
        if (!added[0]) {
            total.decrementAndGet();
            throw new StreamCapacityException("Too many live streams for this event; try again later");
        }
        Runnable remove = () -> remove(eventId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
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
            total.addAndGet(-targets.size());
            targets.forEach(SseEmitter::complete);
        }
    }

    int subscriberCount(long eventId) {
        List<SseEmitter> targets = emitters.get(eventId);
        return targets == null ? 0 : targets.size();
    }

    int totalSubscribers() {
        return total.get();
    }

    /** Drops one stream; safe to call more than once (completion, timeout and send errors all call it). */
    void remove(long eventId, SseEmitter emitter) {
        boolean[] removed = {false};
        emitters.computeIfPresent(eventId, (id, list) -> {
            removed[0] = list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
        if (removed[0]) {
            total.decrementAndGet();
        }
    }
}
