package dev.surge.booking.support;

import com.redis.testcontainers.RedisContainer;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return new MySQLContainer("mysql:8.4");
    }

    @Bean
    @ServiceConnection(name = "redis")
    RedisContainer redis() {
        return new RedisContainer("redis:7-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.1.2");
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer("rabbitmq:4-management");
    }

    /** catalog.v1 is owned by catalog-service; the test broker needs it so booking's listener can subscribe. */
    @Bean
    NewTopic catalogTopic() {
        return new NewTopic("catalog.v1", 3, (short) 1);
    }
}
