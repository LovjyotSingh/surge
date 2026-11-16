package dev.surge.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalTokenSigner;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Which PaymentGateway is wired for which surge.payment.mode, without starting the application. */
class PaymentModeWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(CircuitBreakerRegistry.class, CircuitBreakerRegistry::ofDefaults)
            .withBean(RetryRegistry.class, RetryRegistry::ofDefaults)
            .withBean(InternalTokenSigner.class, () -> IdentityTokens.signer("svc:booking"))
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
            .withUserConfiguration(PaymentClientConfig.class, FakePaymentGateway.class);

    @Test
    void httpModeWiresTheHttpGatewayOnly() {
        runner.withPropertyValues("surge.payment.mode=http", "surge.payment.base-url=http://payment-service")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(PaymentGateway.class);
                    assertThat(ctx.getBean(PaymentGateway.class)).isInstanceOf(HttpPaymentGateway.class);
                });
    }

    /** A payment base URL that names another service would sign tokens for the wrong audience: no start. */
    @Test
    void aBaseUrlNamingAnotherServiceFailsStartup() {
        runner.withPropertyValues("surge.payment.mode=http", "surge.payment.base-url=http://booking-service")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("wrong service"));
    }

    @Test
    void fakeIsTheDefaultAndExplicitFakeAgrees() {
        runner.run(ctx -> assertThat(ctx.getBean(PaymentGateway.class)).isInstanceOf(FakePaymentGateway.class));
        runner.withPropertyValues("surge.payment.mode=fake").run(ctx -> {
            assertThat(ctx).hasSingleBean(PaymentGateway.class);
            assertThat(ctx.getBean(PaymentGateway.class)).isInstanceOf(FakePaymentGateway.class);
        });
    }
}
