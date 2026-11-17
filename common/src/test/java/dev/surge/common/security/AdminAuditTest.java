package dev.surge.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.surge.common.web.RequestIdFilter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** SEC-19: one INFO line per admin action, naming the verified actor and the request id, and nothing else. */
class AdminAuditTest {

    private static final String AUDIENCE = "booking-service";

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
        MDC.clear();
    }

    private List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void anActionIsOneInfoLineWithTheVerifiedActorAndTheRequestId() {
        MDC.put(RequestIdFilter.MDC_KEY, "rid-42");
        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE,
                () -> AdminAudit.log("booking.reset", "event:1"));

        assertThat(lines()).containsExactly(
                "ADMIN_ACTION actor=u-admin action=booking.reset target=event:1 requestId=rid-42");
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void withoutAnIdentityOrARequestIdTheFieldsSayDash() {
        IdentityTokens.runAs(null, AUDIENCE, () -> AdminAudit.log("catalog.import", null));
        assertThat(lines()).containsExactly("ADMIN_ACTION actor=- action=catalog.import target=- requestId=-");
    }

    @Test
    void valuesCannotForgeFieldsOrLines() {
        MDC.put(RequestIdFilter.MDC_KEY, "r 1");
        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE,
                () -> AdminAudit.log("x y", "a b\nADMIN_ACTION actor=evil"));

        assertThat(lines()).hasSize(1);
        String line = lines().get(0);
        assertThat(line).doesNotContain("\n").doesNotContain("actor=evil")
                .isEqualTo("ADMIN_ACTION actor=u-admin action=x_y target=a_b_ADMIN_ACTION_actor_evil requestId=r_1");
        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE,
                () -> AdminAudit.log("long", "t".repeat(500)));
        assertThat(lines().get(1)).contains("target=" + "t".repeat(AdminAudit.MAX_VALUE) + " ");
    }

    @Test
    void anAdminPassesTheCheckSilently() {
        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE,
                () -> assertThat(AdminAudit.requireAdmin("payment.chaos.put").subject()).isEqualTo("u-admin"));
        assertThat(lines()).isEmpty();
    }

    @Test
    void aRefusalIsOneLineAndTheSameProblem() {
        MDC.put(RequestIdFilter.MDC_KEY, "rid-7");
        IdentityTokens.runAs(IdentityTokens.ordinaryUser(AUDIENCE, "u-1"), AUDIENCE,
                () -> assertThatThrownBy(() -> AdminAudit.requireAdmin("payment.chaos.put"))
                        .isInstanceOfSatisfying(InternalIdentityException.class,
                                e -> assertThat(e.getStatusCode().value()).isEqualTo(403)));
        IdentityTokens.runAs(IdentityTokens.anonymous(AUDIENCE), AUDIENCE,
                () -> assertThatThrownBy(() -> AdminAudit.requireAdmin("payment.chaos.get"))
                        .isInstanceOfSatisfying(InternalIdentityException.class,
                                e -> assertThat(e.getStatusCode().value()).isEqualTo(401)));
        IdentityTokens.runAs(IdentityTokens.service("svc:notification", AUDIENCE), AUDIENCE,
                () -> assertThatThrownBy(() -> AdminAudit.requireAdmin("booking.admin"))
                        .isInstanceOf(InternalIdentityException.class));
        IdentityTokens.runAs(null, AUDIENCE, () -> assertThatThrownBy(() -> AdminAudit.requireAdmin("booking.admin"))
                .isInstanceOf(InternalIdentityException.class));

        assertThat(lines()).containsExactly(
                "ADMIN_ACTION_REFUSED actor=u-1 action=payment.chaos.put target=- requestId=rid-7 status=403",
                "ADMIN_ACTION_REFUSED actor=anonymous action=payment.chaos.get target=- requestId=rid-7 status=401",
                "ADMIN_ACTION_REFUSED actor=svc:notification action=booking.admin target=- requestId=rid-7 status=403",
                "ADMIN_ACTION_REFUSED actor=- action=booking.admin target=- requestId=rid-7 status=401");
    }
}
