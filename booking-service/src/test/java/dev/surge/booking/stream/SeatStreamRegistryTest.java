package dev.surge.booking.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.booking.error.StreamCapacityException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** SEC-09: seat streams are capped per event and in total, and an emitter lives at most 10 minutes. */
class SeatStreamRegistryTest {

    private final SeatStreamRegistry registry = new SeatStreamRegistry(JsonMapper.builder().build(), 2, 3);

    @Test
    void anEventTakesAtMostItsCap() {
        registry.subscribe(1);
        registry.subscribe(1);
        assertThatThrownBy(() -> registry.subscribe(1)).isInstanceOf(StreamCapacityException.class);
        assertThat(registry.subscriberCount(1)).isEqualTo(2);
        // another event still has room
        registry.subscribe(2);
        assertThat(registry.subscriberCount(2)).isEqualTo(1);
    }

    @Test
    void allEventsTogetherTakeAtMostTheTotalCap() {
        registry.subscribe(1);
        registry.subscribe(2);
        registry.subscribe(3);
        assertThatThrownBy(() -> registry.subscribe(4)).isInstanceOf(StreamCapacityException.class);
        assertThat(registry.totalSubscribers()).isEqualTo(3);
    }

    @Test
    void aClosedStreamFreesItsPlace() {
        SseEmitter first = registry.subscribe(1);
        registry.subscribe(1);
        registry.remove(1, first);
        registry.remove(1, first); // removing twice frees one place, not two
        assertThat(registry.totalSubscribers()).isEqualTo(1);
        registry.subscribe(1);
        assertThatThrownBy(() -> registry.subscribe(1)).isInstanceOf(StreamCapacityException.class);
    }

    @Test
    void completingAnEventFreesAllItsPlaces() {
        registry.subscribe(1);
        registry.subscribe(1);
        registry.subscribe(2);
        registry.completeAll(1);
        assertThat(registry.totalSubscribers()).isEqualTo(1);
        registry.subscribe(1);
        registry.subscribe(1);
        assertThat(registry.totalSubscribers()).isEqualTo(3);
    }

    @Test
    void anEmitterTimesOutAfterTenMinutes() {
        assertThat(registry.subscribe(1).getTimeout()).isEqualTo(Duration.ofMinutes(10).toMillis());
    }
}
