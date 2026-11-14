package dev.surge.notification.support;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
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
    S3MockContainer s3Mock() {
        return new S3MockContainer(DockerImageName.parse("adobe/s3mock:5.2.3"));
    }

    @Bean
    MailpitContainer mailpit() {
        return new MailpitContainer();
    }

    /** Points the S3 client and the mail sender at the containers (the registrar form of @DynamicPropertySource). */
    @Bean
    DynamicPropertyRegistrar containerProperties(S3MockContainer s3Mock, MailpitContainer mailpit) {
        return registry -> {
            registry.add("surge.s3.endpoint", s3Mock::getHttpEndpoint);
            registry.add("spring.mail.host", mailpit::smtpHost);
            registry.add("spring.mail.port", mailpit::smtpPort);
        };
    }
}
