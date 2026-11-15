package dev.surge.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.surge.booking.error.ApiExceptionHandler;
import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalIdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * The second layer behind the identity chain: {@code @CurrentUser} and the admin interceptor read only the verified
 * identity ({@code CurrentIdentity}), never a header, and answer 401 missing-user / 403 forbidden on their own.
 */
class IdentityReadersTest {

    private static final String AUDIENCE = "booking-service";

    /** A request that also carries forged legacy headers: they must make no difference. */
    private static MockHttpServletRequest forged() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bookings/holds");
        request.addHeader("X-User-Id", "u-forged");
        request.addHeader("X-User-Roles", "ADMIN");
        return request;
    }

    private static Object resolveUser() throws Exception {
        return new CurrentUserArgumentResolver().resolveArgument(null, null, new ServletWebRequest(forged()), null);
    }

    private static void admin() {
        new AdminAccess().preHandle(forged(), new MockHttpServletResponse(), new Object());
    }

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InternalIdentityException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void currentUserIsTheTokensSubject() {
        IdentityTokens.runAs(IdentityTokens.ordinaryUser(AUDIENCE, "u-7"), AUDIENCE, () -> {
            try {
                assertThat(resolveUser()).isEqualTo("u-7");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void currentUserNeedsASignedInGatewayUser() {
        Runnable resolve = () -> {
            try {
                resolveUser();
            } catch (InternalIdentityException e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
        IdentityTokens.runAs(null, AUDIENCE, () -> assertStatus(resolve, 401));
        IdentityTokens.runAs(IdentityTokens.anonymous(AUDIENCE), AUDIENCE, () -> assertStatus(resolve, 401));
        IdentityTokens.runAs(IdentityTokens.service("svc:notification", AUDIENCE), AUDIENCE,
                () -> assertStatus(resolve, 401));
    }

    /** booking's handler has a catch-all for Exception: the identity problems must not fall into it (500). */
    @Test
    void theServicesOwnHandlerRendersTheIdentityProblems() throws Exception {
        var resolver = new ExceptionHandlerMethodResolver(ApiExceptionHandler.class);
        var handler = new ApiExceptionHandler();
        var request = new ServletWebRequest(new MockHttpServletRequest());
        for (InternalIdentityException e : new InternalIdentityException[] {InternalIdentityException.missingUser(),
                InternalIdentityException.forbidden()}) {
            assertThat(resolver.resolveMethod(e).getName()).isEqualTo("handleException");
            ResponseEntity<Object> response = handler.handleException(e, request);
            assertThat(response.getStatusCode()).isEqualTo(e.getStatusCode());
            assertThat(((ProblemDetail) response.getBody()).getType().toString()).isEqualTo(
                    "https://surge.dev/problems/" + (e.getStatusCode().value() == 401 ? "missing-user" : "forbidden"));
        }
    }

    @Test
    void adminAccessNeedsTheExactAdminRole() {
        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE, IdentityReadersTest::admin);
        IdentityTokens.runAs(null, AUDIENCE, () -> assertStatus(IdentityReadersTest::admin, 401));
        IdentityTokens.runAs(IdentityTokens.anonymous(AUDIENCE), AUDIENCE,
                () -> assertStatus(IdentityReadersTest::admin, 401));
        IdentityTokens.runAs(IdentityTokens.ordinaryUser(AUDIENCE, "u-1"), AUDIENCE,
                () -> assertStatus(IdentityReadersTest::admin, 403));
        IdentityTokens.runAs(IdentityTokens.user(AUDIENCE, "u-1", "USER", "SUPERADMIN"), AUDIENCE,
                () -> assertStatus(IdentityReadersTest::admin, 403));
        IdentityTokens.runAs(IdentityTokens.service("svc:notification", AUDIENCE), AUDIENCE,
                () -> assertStatus(IdentityReadersTest::admin, 403));
    }
}
