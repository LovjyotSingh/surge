package dev.surge.booking.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.surge.booking.error.InvalidParameterException;
import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalIdentityException;
import org.junit.jupiter.api.Test;

/**
 * The second layer behind the identity chain, without the chain: {@code InternalOrderController} itself answers
 * {@code svc:notification} and nobody else ({@code CurrentIdentity.requireService}), before it judges or reads
 * anything. Removing that line from the controller makes this test fail.
 */
class InternalOrderControllerIdentityTest {

    private static final String AUDIENCE = "booking-service";

    private final OrderReader reader = mock(OrderReader.class);
    private final InternalOrderController controller = new InternalOrderController(reader);

    @Test
    void notificationGetsTheOwnersOrder() {
        OrderResponse order = mock(OrderResponse.class);
        when(reader.getForUser("u-1", 7L)).thenReturn(order);
        IdentityTokens.runAs(IdentityTokens.service("svc:notification", AUDIENCE), AUDIENCE,
                () -> assertThat(controller.get("7", "u-1")).isSameAs(order));
    }

    @Test
    void anyoneElseIsRefusedBeforeAnythingIsJudgedOrRead() {
        IdentityTokens.runAs(null, AUDIENCE, () -> assertStatus(() -> controller.get("7", "u-1"), 401));
        for (String token : new String[] {IdentityTokens.service("svc:booking", AUDIENCE),
                IdentityTokens.admin(AUDIENCE, "u-admin"), IdentityTokens.ordinaryUser(AUDIENCE, "u-1"),
                IdentityTokens.anonymous(AUDIENCE)}) {
            IdentityTokens.runAs(token, AUDIENCE, () -> {
                assertStatus(() -> controller.get("7", "u-1"), 403);
                // even a malformed order id or user id gets the identity answer, never a 400 about the parameters
                assertStatus(() -> controller.get("not-a-number", null), 403);
            });
        }
        verifyNoInteractions(reader);
    }

    @Test
    void notificationStillNeedsAWellFormedOrderIdAndUserId() {
        IdentityTokens.runAs(IdentityTokens.service("svc:notification", AUDIENCE), AUDIENCE, () -> {
            for (String bad : new String[] {"x", "", "-1", "0", "1.5", "99999999999999999999"}) {
                assertThatThrownBy(() -> controller.get(bad, "u-1")).as(bad)
                        .isInstanceOf(InvalidParameterException.class);
            }
            assertThatThrownBy(() -> controller.get("7", "svc:notification"))
                    .isInstanceOf(InvalidParameterException.class);
        });
        verifyNoInteractions(reader);
    }

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InternalIdentityException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }
}
