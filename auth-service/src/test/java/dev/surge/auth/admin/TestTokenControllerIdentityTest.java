package dev.surge.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.surge.auth.error.InvalidBodyException;
import dev.surge.auth.jwt.TokenService;
import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalIdentityException;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The second layer behind the identity chain: minting load-test tokens needs a gateway ADMIN, checked by the
 * controller itself before the body is even read (no Spring context, no chain in front).
 */
class TestTokenControllerIdentityTest {

    private static final String AUDIENCE = "auth-service";

    private final TokenService tokens = mock(TokenService.class);
    private final TestTokenController controller = new TestTokenController(tokens, JsonMapper.builder().build(),
            mock(Validator.class));

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InternalIdentityException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void anAdminGetsPastTheCheck() {
        // the deliberately broken body is what fails next: the identity check let the admin through
        IdentityTokens.runAs(IdentityTokens.admin(AUDIENCE, "u-admin"), AUDIENCE,
                () -> assertThatThrownBy(() -> controller.mint("not json")).isInstanceOf(InvalidBodyException.class));
    }

    @Test
    void anyoneElseIsRefusedBeforeTheBodyIsRead() {
        Runnable mint = () -> controller.mint("{\"prefix\":\"lt-x\",\"count\":1}");
        IdentityTokens.runAs(null, AUDIENCE, () -> assertStatus(mint, 401));
        IdentityTokens.runAs(IdentityTokens.anonymous(AUDIENCE), AUDIENCE, () -> assertStatus(mint, 401));
        IdentityTokens.runAs(IdentityTokens.ordinaryUser(AUDIENCE, "u-1"), AUDIENCE, () -> assertStatus(mint, 403));
        IdentityTokens.runAs(IdentityTokens.user(AUDIENCE, "u-1", "USER", "SUPERADMIN"), AUDIENCE,
                () -> assertStatus(mint, 403));
        IdentityTokens.runAs(IdentityTokens.service("svc:notification", AUDIENCE), AUDIENCE,
                () -> assertStatus(mint, 403));
        verifyNoInteractions(tokens);
    }
}
