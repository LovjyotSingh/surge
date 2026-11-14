package dev.surge.catalog.support;

import dev.surge.common.messaging.MessageTypes;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return new MySQLContainer("mysql:8.4").withDatabaseName("catalog");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.1.2");
    }

    @Bean
    @ServiceConnection
    ElasticsearchContainer elasticsearch() {
        return new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.2.4")
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
    }

    /** Booking owns orders.v1 in the real system; catalog's tests need it to exist so the consumer can subscribe. */
    @Bean
    NewTopic ordersTopic() {
        return new NewTopic(MessageTypes.ORDERS_TOPIC, 3, (short) 1);
    }
}
