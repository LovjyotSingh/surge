package dev.surge.booking.support;

import dev.surge.outbox.OutboxPublisher;
import dev.surge.booking.payment.PaymentGateway;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * One shared Spring context for every integration test (never add field-level mocks or test properties: each
 * variation would start a second context with new containers).
 *
 * <p>PaymentGateway is wrapped in a Mockito spy (delegating to the real FakePaymentGateway), as are OutboxPublisher
 * (the real RoutingOutboxPublisher) and KafkaTemplate; tests can stub declines or publish failures and verify refunds, and Mockito
 * resets the spies after each test. The outbox poll, hold sweep and payment reconcile intervals are pushed to PT1H so the schedulers
 * never race tests; tests drive them explicitly through {@code OutboxRelay.relayBatch()},
 * {@code OutboxPoller.poll()} and {@code HoldExpirySweeper.sweep()}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {"surge.outbox.poll-interval=PT1H", "surge.hold.sweep-interval=PT1H",
        "surge.payment.reconcile-interval=PT1H", "eureka.client.enabled=false", "spring.cloud.config.enabled=false"})
@AutoConfigureMockMvc
@MockitoSpyBean(types = {PaymentGateway.class, OutboxPublisher.class, KafkaTemplate.class})
@Import({TestcontainersConfig.class, TestData.class})
public @interface IntegrationTest {
}
