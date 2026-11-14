package dev.surge.notification.support;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return new MySQLContainer("mysql:8.4").withDatabaseName("notification");
    }

    @Bean
    S3MockContainer s3Mock() {
        return new S3MockContainer(DockerImageName.parse("adobe/s3mock:5.2.3"));
    }

    /** Points the S3 client at the mock (the registrar form of @DynamicPropertySource, usable in a config class). */
    @Bean
    DynamicPropertyRegistrar s3Properties(S3MockContainer s3Mock) {
        return registry -> registry.add("surge.s3.endpoint", s3Mock::getHttpEndpoint);
    }
}
