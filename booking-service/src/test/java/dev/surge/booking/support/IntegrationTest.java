package dev.surge.booking.support;

import dev.surge.booking.outbox.OutboxPublisher;
import dev.surge.booking.payment.PaymentGateway;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * One shared Spring context for every integration test. PaymentGateway is wrapped in a Mockito spy (delegating to
 * the real FakePaymentGateway), as is OutboxPublisher (real LoggingOutboxPublisher); the outbox scheduler is pushed
 * to PT1H so it never races tests, which call OutboxRelay.relayBatch() explicitly. Tests can stub declines and verify refunds; it resets after each test.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "surge.outbox.poll-interval=PT1H")
@AutoConfigureMockMvc
@MockitoSpyBean(types = {PaymentGateway.class, OutboxPublisher.class})
@Import({TestcontainersConfig.class, TestData.class})
public @interface IntegrationTest {
}
