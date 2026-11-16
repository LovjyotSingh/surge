package dev.surge.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalIdentityException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The second layer behind the identity chain: {@code /me} names the user the verified token states, and nobody
 * else, without the chain in front (no Spring context).
 */
class AuthControllerIdentityTest {

    private static final String AUDIENCE = "auth-service";

    private final UserLookup lookup = mock(UserLookup.class);
    private final AuthController controller = new AuthController(null, lookup, null);

    @Test
    void meIsTheUserTheTokenStates() {
        UserView view = new UserView("u-1", "u-1@example.com", "Ada", List.of("USER"));
        when(lookup.find("u-1")).thenReturn(view);
        IdentityTokens.runAs(IdentityTokens.ordinaryUser(AUDIENCE, "u-1"), AUDIENCE,
                () -> assertThat(controller.me()).isSameAs(view));
    }

    @Test
    void meNeedsASignedInUser() {
        for (String token : new String[] {null, IdentityTokens.anonymous(AUDIENCE),
                IdentityTokens.service("svc:notification", AUDIENCE)}) {
            IdentityTokens.runAs(token, AUDIENCE, () -> assertThatThrownBy(controller::me)
                    .isInstanceOfSatisfying(InternalIdentityException.class, e -> {
                        assertThat(e.getStatusCode().value()).isEqualTo(401);
                        assertThat(e.getBody().getType().toString()).endsWith("/missing-user");
                    }));
        }
        verifyNoInteractions(lookup);
    }
}
