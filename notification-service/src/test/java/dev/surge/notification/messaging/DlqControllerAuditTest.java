package dev.surge.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.surge.common.security.AdminAudit;
import dev.surge.common.security.IdentityTokens;
import dev.surge.notification.error.ReplayFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * SEC-19, partial failure: a replay that re-sent some messages and then failed has acted, so it is audited with the
 * count and {@code failed}, and the same problem still reaches the caller.
 */
class DlqControllerAuditTest {

    private static final String AUDIENCE = "notification-service";

    private final DlqOperations dlq = mock(DlqOperations.class);
    private final DlqController controller = new DlqController(dlq);
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
    void aPartialReplayIsAuditedWithItsCountAndRethrown() {
        ReplayFailedException failure = new ReplayFailedException("The broker confirm timed out", 2, null);
        when(dlq.replay(5)).thenThrow(failure);

        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE,
                () -> assertThatThrownBy(() -> controller.replay("5")).isSameAs(failure));

        assertThat(failure.replayed()).isEqualTo(2);
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "ADMIN_ACTION actor=u-admin action=notification.dlq.replay target=queue:tickets.dlq,replayed:2,failed "
                        + "requestId=-");
    }
}
