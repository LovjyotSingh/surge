package dev.surge.notification.support;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import dev.surge.common.messaging.MessageTypes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return new MySQLContainer("mysql:8.4").withDatabaseName("notification");
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer("rabbitmq:4-management");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.1.2");
    }

    /** Booking owns orders.v1 in the real system; the audit consumer needs it to exist so it can subscribe. */
    @Bean
    NewTopic ordersTopic() {
        return new NewTopic(MessageTypes.ORDERS_TOPIC, 3, (short) 1);
    }

    @Bean
    S3MockContainer s3Mock() {
        return new S3MockContainer(DockerImageName.parse("adobe/s3mock:5.2.3"));
    }

    @Bean
    MailpitContainer mailpit() {
        return new MailpitContainer();
    }

    /** One audit directory for the single shared context; the audit tests empty it in @BeforeEach. */
    public static final Path AUDIT_DIR = createAuditDir();

    private static Path createAuditDir() {
        try {
            Path dir = Files.createTempDirectory("surge-audit-test");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Points the S3 client and the mail sender at the containers (the registrar form of @DynamicPropertySource). */
    @Bean
    DynamicPropertyRegistrar containerProperties(S3MockContainer s3Mock, MailpitContainer mailpit) {
        return registry -> {
            registry.add("surge.notification.audit-dir", AUDIT_DIR::toString);
            registry.add("surge.s3.endpoint", s3Mock::getHttpEndpoint);
            registry.add("spring.mail.host", mailpit::smtpHost);
            registry.add("spring.mail.port", mailpit::smtpPort);
        };
    }
}
