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
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * SEC-04/05/06/15, client side: with SURGE_ENV=prod a service refuses to start while a store or platform server it
 * uses has no credentials, or still has the development ones. Dev and tests need none.
 */
class StoreCredentialsTest {

    private static final String REFUSAL = "SURGE_ENV=prod needs credentials for every store this service uses";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StoreCredentialsAutoConfiguration.class));

    /** Everything booking-service needs in prod (it uses every kind of store but Elasticsearch). */
    private static final Map<String, String> BOOKING_PROD = Map.ofEntries(
            Map.entry("spring.application.name", "booking-service"),
            Map.entry("surge.env", "prod"),
            Map.entry("spring.datasource.username", "booking_app"),
            Map.entry("spring.datasource.password", "mysql-secret"),
            Map.entry("spring.kafka.properties.security.protocol", "SASL_PLAINTEXT"),
            Map.entry("surge.kafka.username", "booking"),
            Map.entry("surge.kafka.password", "kafka-secret"),
            Map.entry("spring.rabbitmq.username", "booking"),
            Map.entry("spring.rabbitmq.password", "rabbit-secret"),
            Map.entry("spring.data.redis.password", "redis-secret"),
            Map.entry("eureka.client.service-url.defaultZone", "http://eureka:eureka-secret@discovery:8761/eureka"),
            Map.entry("spring.cloud.config.username", "config"),
            Map.entry("spring.cloud.config.password", "config-secret"));

    private static String[] props(Map<String, String> base, String... overrides) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(base);
        for (String override : overrides) {
            int eq = override.indexOf('=');
            merged.put(override.substring(0, eq), override.substring(eq + 1));
        }
        return merged.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    @Test
    void prodWithEveryCredentialStarts() {
        runner.withPropertyValues(props(BOOKING_PROD)).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void devAndTestsNeedNoCredentials() {
        runner.withPropertyValues("spring.application.name=booking-service")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(props(BOOKING_PROD, "surge.env=dev", "spring.data.redis.password="))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodRefusesEachMissingOrDevelopmentCredential() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("spring.datasource.password=", "MySQL: set MYSQL_USER and MYSQL_PASSWORD"),
                Map.entry("spring.datasource.password=surge", "MySQL: set MYSQL_USER and MYSQL_PASSWORD"),
                Map.entry("spring.datasource.username= ", "MySQL: set MYSQL_USER and MYSQL_PASSWORD"),
                Map.entry("spring.kafka.properties.security.protocol=PLAINTEXT", "Kafka: set KAFKA_SECURITY_PROTOCOL"),
                Map.entry("surge.kafka.username=", "Kafka: set KAFKA_SECURITY_PROTOCOL"),
                Map.entry("surge.kafka.password=", "Kafka: set KAFKA_SECURITY_PROTOCOL"),
                Map.entry("surge.kafka.password=quo\"te", "Kafka: set KAFKA_SECURITY_PROTOCOL"),
                Map.entry("surge.kafka.password=back\\\\slash", "Kafka: set KAFKA_SECURITY_PROTOCOL"),
                Map.entry("surge.kafka.username=bo\"oking", "Kafka: set KAFKA_SECURITY_PROTOCOL"),
                Map.entry("surge.kafka.username=bo\\\\oking", "Kafka: set KAFKA_SECURITY_PROTOCOL"),
                Map.entry("spring.rabbitmq.username=guest", "RabbitMQ: set RABBIT_USER and RABBIT_PASSWORD"),
                Map.entry("spring.rabbitmq.password=guest", "RabbitMQ: set RABBIT_USER and RABBIT_PASSWORD"),
                Map.entry("spring.rabbitmq.password=", "RabbitMQ: set RABBIT_USER and RABBIT_PASSWORD"),
                Map.entry("spring.data.redis.password=", "Redis: set REDIS_PASSWORD"),
                Map.entry("eureka.client.service-url.defaultZone=http://discovery:8761/eureka",
                        "Eureka: set EUREKA_USER and EUREKA_PASSWORD"),
                Map.entry("eureka.client.service-url.defaultZone=http://surge-dev:dev-only-eureka-password@discovery:"
                        + "8761/eureka", "Eureka: set EUREKA_USER and EUREKA_PASSWORD"),
                Map.entry("eureka.client.service-url.defaultZone=http://eureka:@discovery:8761/eureka",
                        "Eureka: set EUREKA_USER and EUREKA_PASSWORD"),
                // URL-safe only: letters, digits and . _ ~ - (no percent-encoding, no @ : / in the userinfo)
                Map.entry("eureka.client.service-url.defaultZone=http://eureka:pa%40ss@discovery:8761/eureka",
                        "Eureka: set EUREKA_USER and EUREKA_PASSWORD"),
                Map.entry("eureka.client.service-url.defaultZone=http://eu+reka:eureka-pass@discovery:8761/eureka",
                        "Eureka: set EUREKA_USER and EUREKA_PASSWORD"),
                Map.entry("spring.cloud.config.password=", "Config server: set CONFIG_USER and CONFIG_PASSWORD"),
                Map.entry("spring.cloud.config.password=dev-only-config-password",
                        "Config server: set CONFIG_USER and CONFIG_PASSWORD"),
                Map.entry("spring.cloud.config.password=has space", "Config server: set CONFIG_USER and CONFIG_PASSWORD"),
                Map.entry("spring.cloud.config.username=con@fig", "Config server: set CONFIG_USER and CONFIG_PASSWORD"));
        cases.forEach((override, message) -> runner.withPropertyValues(props(BOOKING_PROD, override)).run(context -> {
            assertThat(context).as(override).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().as(override)
                    .hasMessageStartingWith(REFUSAL).hasMessageContaining(message)
                    // only names, never a value
                    .message().doesNotContain("secret").doesNotContain("dev-only");
        }));
    }

    @Test
    void everyProblemIsListedAtOnce() {
        runner.withPropertyValues("spring.application.name=catalog-service", "surge.env=prod").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("MySQL")
                    .hasMessageContaining("Kafka").hasMessageContaining("Elasticsearch")
                    .hasMessageContaining("Eureka").hasMessageContaining("Config server")
                    .message().doesNotContain("RabbitMQ").doesNotContain("Redis");
        });
    }

    @Test
    void eachServiceNeedsOnlyTheStoresItUses() {
        String[] platform = {"surge.env=prod",
                "eureka.client.service-url.defaultZone=http://eureka:eureka-secret@discovery:8761/eureka",
                "spring.cloud.config.username=config", "spring.cloud.config.password=config-secret"};
        runner.withPropertyValues(platform).withPropertyValues("spring.application.name=payment-service")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(platform).withPropertyValues("spring.application.name=gateway")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("Redis: set REDIS_PASSWORD").message().doesNotContain("MySQL"));
        runner.withPropertyValues(platform).withPropertyValues("spring.application.name=gateway",
                "spring.data.redis.password=redis-secret").run(context -> assertThat(context).hasNotFailed());
    }

    /** A service missing from the table would otherwise start in prod with nothing checked. */
    @Test
    void prodRefusesAnApplicationTheTableDoesNotList() {
        runner.withPropertyValues("spring.application.name=mystery-service", "surge.env=prod").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessage("SURGE_ENV=prod: spring.application.name "
                    + "'mystery-service' is not in StoreCredentials.STORES_BY_SERVICE; list the stores it uses");
        });
        runner.withPropertyValues("surge.env=prod").run(context -> assertThat(context.getStartupFailure())
                .rootCause().hasMessageContaining("spring.application.name '' is not in"));
        runner.withPropertyValues("spring.application.name=mystery-service")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aDisabledEurekaOrConfigClientNeedsNoCredentials() {
        runner.withPropertyValues("spring.application.name=payment-service", "surge.env=prod",
                "eureka.client.enabled=false", "spring.cloud.config.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * Every service's own application.yml, with no environment variables: it needs no credentials in dev, sends the
     * development Eureka and config-server credentials (the ones discovery and config-server accept in dev), and is
     * refused in prod.
     */
    @Test
    void everyServicesApplicationYmlIsRefusedInProdUntilTheEnvironmentProvidesCredentials() throws IOException {
        for (String module : List.of("gateway", "auth-service", "booking-service", "catalog-service",
                "payment-service", "notification-service")) {
            StandardEnvironment dev = ymlOnly(module, "dev");
            assertThatCode(() -> StoreCredentials.check(dev)).as(module + " in dev").doesNotThrowAnyException();
            assertThat(dev.getProperty("eureka.client.service-url.defaultZone")).as(module)
                    .isEqualTo("http://surge-dev:dev-only-eureka-password@localhost:8761/eureka");
            assertThat(dev.getProperty("spring.cloud.config.username")).as(module).isEqualTo("surge-dev");
            assertThat(dev.getProperty("spring.cloud.config.password")).as(module)
                    .isEqualTo("dev-only-config-password");
            assertThat(StoreCredentials.STORES_BY_SERVICE).as(module).containsKey(dev.getProperty(
                    "spring.application.name"));
            StandardEnvironment prod = ymlOnly(module, "prod");
            assertThatThrownBy(() -> StoreCredentials.check(prod)).as(module + " in prod")
                    .hasMessageStartingWith(REFUSAL).hasMessageContaining("Eureka")
                    .hasMessageContaining("Config server");
        }
    }

    /** A module's application.yml alone (no OS environment), with surge.env set. */
    private static StandardEnvironment ymlOnly(String module, String surgeEnv) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        new ArrayList<>(sources.stream().map(PropertySource::getName).toList()).forEach(sources::remove);
        sources.addFirst(new MapPropertySource("test", Map.of("surge.env", surgeEnv)));
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(module,
                new FileSystemResource(Path.of("..", module, "src/main/resources/application.yml")))) {
            sources.addLast(source);
        }
        return environment;
    }
}
