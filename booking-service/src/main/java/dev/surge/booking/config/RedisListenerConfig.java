package dev.surge.booking.config;

import dev.surge.booking.stream.SeatChannelListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisListenerConfig {

    @Bean
    RedisMessageListenerContainer seatListenerContainer(RedisConnectionFactory connectionFactory,
                                                        SeatChannelListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new PatternTopic("seats:*"));
        return container;
    }
}
