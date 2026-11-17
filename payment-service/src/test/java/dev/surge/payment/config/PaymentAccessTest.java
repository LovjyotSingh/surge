package dev.surge.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalIdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The caller checks that run before argument binding (behind the identity chain's own rules): a caller that may not
 * use an endpoint never gets a 400 that describes its body or parameters.
 */
class PaymentAccessTest {

    private static final String AUDIENCE = "payment-service";

    private static void call(HandlerInterceptor interceptor) {
        try {
            assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                    .isTrue();
        } catch (InternalIdentityException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InternalIdentityException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void theAdminRoutesNeedAGatewayAdmin() {
        HandlerInterceptor admin = WebConfig.adminAccess();
        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE, () -> call(admin));
        IdentityTokens.runAs(null, AUDIENCE, () -> assertStatus(() -> call(admin), 401));
        IdentityTokens.runAs(IdentityTokens.ordinaryUser(AUDIENCE, "u-1"), AUDIENCE,
                () -> assertStatus(() -> call(admin), 403));
        IdentityTokens.runAs(IdentityTokens.service("svc:booking", AUDIENCE), AUDIENCE,
                () -> assertStatus(() -> call(admin), 403));
    }

    @Test
    void thePaymentsApiNeedsBooking() {
        HandlerInterceptor booking = WebConfig.bookingAccess();
        IdentityTokens.runAs(IdentityTokens.service("svc:booking", AUDIENCE), AUDIENCE, () -> call(booking));
        IdentityTokens.runAs(null, AUDIENCE, () -> assertStatus(() -> call(booking), 401));
        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE,
                () -> assertStatus(() -> call(booking), 403));
        IdentityTokens.runAs(IdentityTokens.service("svc:notification", AUDIENCE), AUDIENCE,
                () -> assertStatus(() -> call(booking), 403));
    }
}
