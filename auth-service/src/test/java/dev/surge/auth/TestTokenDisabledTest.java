package dev.surge.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.auth.admin.TestTokenController;
import dev.surge.auth.jwt.TokenService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Without the property the controller is not registered at all, so the route answers 404. No Boot context. */
class TestTokenDisabledTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(TokenService.class, () -> Mockito.mock(TokenService.class))
            .withUserConfiguration(TestTokenController.class);

    @Test
    void absentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TestTokenController.class));
    }

    @Test
    void absentWhenExplicitlyFalse() {
        runner.withPropertyValues("surge.auth.test-tokens.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TestTokenController.class));
    }

    @Test
    void presentWhenEnabled() {
        runner.withPropertyValues("surge.auth.test-tokens.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(TestTokenController.class));
    }
}
