package dev.surge.notification.support;

import dev.surge.notification.booking.BookingClient;
import dev.surge.notification.catalog.CatalogClient;
import dev.surge.notification.mail.TicketMailer;
import dev.surge.notification.messaging.TicketWorkflow;
import dev.surge.notification.ticket.TicketStore;
import dev.surge.notification.user.UserDirectory;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * One shared Spring context for every integration test (never add field-level mocks or test properties: each
 * variation would start a second context with new containers). The three remote clients are spied at type level, so
 * each test stubs them with {@code doReturn(..).when(..)}; the store, mailer and workflow are spied to inject
 * failures and to count attempts.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.config.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@MockitoSpyBean(types = {BookingClient.class, CatalogClient.class, UserDirectory.class, TicketStore.class,
        TicketMailer.class, TicketWorkflow.class})
public @interface IntegrationTest {
}
