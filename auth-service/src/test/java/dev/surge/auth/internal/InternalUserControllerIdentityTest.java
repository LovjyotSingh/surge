package dev.surge.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.surge.auth.api.UserLookup;
import dev.surge.auth.api.UserView;
import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalIdentityException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The second layer behind the identity chain: the user lookup answers notification and nobody else. */
class InternalUserControllerIdentityTest {

    private static final String AUDIENCE = "auth-service";

    private final UserLookup lookup = mock(UserLookup.class);
    private final InternalUserController controller = new InternalUserController(lookup);

    @Test
    void notificationGetsTheUser() {
        UserView view = new UserView("u-1", "u-1@example.com", "Ada", List.of("USER"));
        when(lookup.find("u-1")).thenReturn(view);
        IdentityTokens.runAs(IdentityTokens.service("svc:notification", AUDIENCE), AUDIENCE,
                () -> assertThat(controller.get("u-1")).isSameAs(view));
    }

    @Test
    void anyoneElseIsRefusedBeforeAnythingIsRead() {
        IdentityTokens.runAs(null, AUDIENCE, () -> assertThatThrownBy(() -> controller.get("u-1"))
                .isInstanceOfSatisfying(InternalIdentityException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(401)));
        for (String token : new String[] {IdentityTokens.service("svc:booking", AUDIENCE),
                IdentityTokens.admin(AUDIENCE, "u-admin"), IdentityTokens.ordinaryUser(AUDIENCE, "u-1"),
                IdentityTokens.anonymous(AUDIENCE)}) {
            IdentityTokens.runAs(token, AUDIENCE, () -> assertThatThrownBy(() -> controller.get("u-1"))
                    .isInstanceOfSatisfying(InternalIdentityException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(403)));
        }
        verifyNoInteractions(lookup);
    }
}
