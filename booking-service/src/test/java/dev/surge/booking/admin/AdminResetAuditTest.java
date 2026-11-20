package dev.surge.booking.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.surge.booking.error.EventNotFoundException;
import dev.surge.common.security.AdminAudit;
import dev.surge.common.security.IdentityTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * SEC-19, failure path: a reset that fails part-way (after the event was found) may already have changed data, so
 * it is audited too, marked {@code failed}, and the error still reaches the caller. An unknown event changed nothing:
 * no line.
 */
class AdminResetAuditTest {

    private static final String AUDIENCE = "booking-service";

    private final EventResetService resetService = mock(EventResetService.class);
    private final AdminController controller = new AdminController(null, null, resetService, null, null);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AdminAudit.LOGGER);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void aResetThatFailsPartWayIsAuditedAsFailedAndRethrown() {
        IllegalStateException redisDown = new IllegalStateException("redis down");
        doThrow(redisDown).when(resetService).reset(1L, 0);

        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE,
                () -> assertThatThrownBy(() -> controller.reset(1L, 0)).isSameAs(redisDown));

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "ADMIN_ACTION actor=u-admin action=booking.reset target=event:1,failed requestId=-");
    }

    @Test
    void anUnknownEventChangedNothingAndIsNotAudited() {
        doThrow(new EventNotFoundException(999L)).when(resetService).reset(999L, 0);

        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE,
                () -> assertThatThrownBy(() -> controller.reset(999L, 0))
                        .isInstanceOf(EventNotFoundException.class));

        assertThat(appender.list).isEmpty();
    }
}
