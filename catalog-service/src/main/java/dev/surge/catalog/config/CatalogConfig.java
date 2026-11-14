package dev.surge.catalog.config;

import dev.surge.common.messaging.MessageTypes;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock and the {@code catalog.v1} topic that catalog owns (booking consumes it). */
@Configuration(proxyBeanMethods = false)
public class CatalogConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    NewTopic catalogTopic() {
        return new NewTopic(MessageTypes.CATALOG_TOPIC, 3, (short) 1);
    }
}
