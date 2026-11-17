package dev.surge.auth.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mysql.MySQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return new MySQLContainer("mysql:8.4").withDatabaseName("auth");
    }

    /** Catches the verification emails (S5), as notification-service's tests do for tickets. */
    @Bean
    MailpitContainer mailpit() {
        return new MailpitContainer();
    }

    /** Points the mail sender at Mailpit (the registrar form of @DynamicPropertySource). */
    @Bean
    DynamicPropertyRegistrar mailProperties(MailpitContainer mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::smtpHost);
            registry.add("spring.mail.port", mailpit::smtpPort);
        };
    }
}
