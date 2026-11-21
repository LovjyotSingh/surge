package dev.surge.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * SEC-16, S10: on a public deployment the services that send email (auth: verification codes; notification: tickets)
 * refuse to start unless they use a real SMTP provider: SMTP AUTH, STARTTLS required and a real From address. Local
 * and private runs (Mailpit: no auth, no TLS) are never checked.
 */
class MailSettingsTest {

    private static final String REFUSAL = "SURGE_PUBLIC_DEPLOYMENT=true needs a real SMTP provider";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSettingsAutoConfiguration.class));

    private static final Map<String, String> PROVIDER = Map.ofEntries(
            Map.entry("spring.application.name", "notification-service"),
            Map.entry("surge.security.public-deployment", "true"),
            Map.entry("spring.mail.host", "smtp.provider.example"),
            Map.entry("spring.mail.port", "587"),
            Map.entry("spring.mail.username", "surge-smtp-user"),
            Map.entry("spring.mail.password", "smtp-secret-value"),
            Map.entry("spring.mail.properties.mail.smtp.auth", "true"),
            Map.entry("spring.mail.properties.mail.smtp.starttls.enable", "true"),
            Map.entry("spring.mail.properties.mail.smtp.starttls.required", "true"),
            Map.entry("surge.mail.from", "Surge <tickets@tickets.example.com>"));

    private static String[] props(Map<String, String> base, String... overrides) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(base);
        for (String override : overrides) {
            int eq = override.indexOf('=');
            merged.put(override.substring(0, eq), override.substring(eq + 1));
        }
        return merged.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    @Test
    void aRealProviderStarts() {
        runner.withPropertyValues(props(PROVIDER)).run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(props(PROVIDER, "spring.application.name=auth-service"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void privateRunsAndServicesThatSendNoMailAreNotChecked() {
        runner.withPropertyValues("spring.application.name=notification-service", "spring.mail.host=mailpit")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.application.name=auth-service", "surge.env=prod",
                "surge.security.public-deployment=false").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.application.name=payment-service", "surge.security.public-deployment=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void eachMissingProtectionIsRefusedByName() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("spring.mail.host=", "MAIL_HOST"),
                Map.entry("spring.mail.username=", "MAIL_USERNAME"),
                Map.entry("spring.mail.password=", "MAIL_PASSWORD"),
                Map.entry("spring.mail.properties.mail.smtp.auth=false", "MAIL_SMTP_AUTH=true"),
                Map.entry("spring.mail.properties.mail.smtp.starttls.enable=false", "MAIL_STARTTLS=true"),
                Map.entry("spring.mail.properties.mail.smtp.starttls.required=false", "MAIL_STARTTLS=true"),
                Map.entry("surge.mail.from=", "MAIL_FROM"),
                Map.entry("surge.mail.from=tickets@surge.local", "MAIL_FROM"),
                Map.entry("surge.mail.from=Surge <No-Reply@SURGE.LOCAL>", "MAIL_FROM"));
        cases.forEach((override, message) -> runner.withPropertyValues(props(PROVIDER, override)).run(context -> {
            assertThat(context).as(override).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().as(override)
                    .hasMessageStartingWith(REFUSAL).hasMessageContaining(message)
                    .message().doesNotContain("smtp-secret-value").doesNotContain("surge-smtp-user");
        }));
    }

    /**
     * auth's and notification's own application.yml: Mailpit's settings by default, which a public deployment
     * refuses; with the variables compose.prod.yaml sets (MAIL_PASSWORD from a secret file), they start.
     */
    @Test
    void theMailingServicesYmlIsRefusedOnAPublicDeploymentUntilTheProdOverlaySetsAProvider() throws IOException {
        for (String module : List.of("auth-service", "notification-service")) {
            StandardEnvironment local = ymlWith(module, Map.of("SURGE_PUBLIC_DEPLOYMENT", "true"));
            assertThatThrownBy(() -> MailSettings.check(local)).as(module).hasMessageStartingWith(REFUSAL)
                    .hasMessageContaining("MAIL_SMTP_AUTH=true").hasMessageContaining("MAIL_STARTTLS=true")
                    .hasMessageContaining("MAIL_FROM");

            StandardEnvironment prod = ymlWith(module, Map.of("SURGE_PUBLIC_DEPLOYMENT", "true",
                    "MAIL_HOST", "smtp.provider.example", "MAIL_USERNAME", "surge-smtp-user",
                    "MAIL_PASSWORD", "smtp-secret-value", "MAIL_SMTP_AUTH", "true", "MAIL_STARTTLS", "true",
                    "MAIL_FROM", "Surge <no-reply@tickets.example.com>"));
            assertThatCode(() -> MailSettings.check(prod)).as(module).doesNotThrowAnyException();
            assertThat(prod.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity")).as(module)
                    .isEqualTo("true");

            StandardEnvironment dev = ymlWith(module, Map.of());
            assertThatCode(() -> MailSettings.check(dev)).as(module).doesNotThrowAnyException();
            assertThat(dev.getProperty("spring.mail.properties.mail.smtp.starttls.enable")).as(module)
                    .isEqualTo("false");
        }
    }

    /** A module's application.yml with the given environment variables (and no OS environment). */
    private static StandardEnvironment ymlWith(String module, Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        List<PropertySource<?>> sources = new ArrayList<>(new YamlPropertySourceLoader().load(module,
                new FileSystemResource(Path.of("..", module, "src/main/resources/application.yml"))));
        sources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}
