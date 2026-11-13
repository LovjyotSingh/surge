package dev.surge.booking.stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.surge.booking.event.SeatState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

class SeatEventPublisherTest {

    @Test
    void publishNeverPropagatesFailures() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doThrow(new RedisConnectionFailureException("down")).when(redis).convertAndSend(anyString(), anyString());
        SeatEventPublisher publisher = new SeatEventPublisher(redis, JsonMapper.builder().build());

        assertThatCode(() -> publisher.publish(1L, List.of(1L, 2L), SeatState.HELD)).doesNotThrowAnyException();
        verify(redis).convertAndSend(eq("seats:1"), anyString());
    }
}
