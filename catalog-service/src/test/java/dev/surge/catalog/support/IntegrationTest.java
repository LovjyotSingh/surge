package dev.surge.catalog.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * One shared Spring context for every integration test (never add field-level mocks or test properties: each
 * variation would start a second context with new containers). The outbox poll interval is pushed to PT1H so the
 * scheduler never races the tests; tests drive the relay explicitly through {@code OutboxRelay.relayBatch()}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {"surge.outbox.poll-interval=PT1H", "eureka.client.enabled=false",
        "spring.cloud.config.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
public @interface IntegrationTest {
}
