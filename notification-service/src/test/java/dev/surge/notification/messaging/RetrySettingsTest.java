package dev.surge.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/**
 * The listener retry in application.yml must outlast the discovery gap after startup (a stale Eureka cache, up to
 * about 5 s once the fetch interval is 5 s): 5 attempts, waits of 1, 2, 4 and 8 s, about 15 s before the
 * dead-letter queue. No broker or context needed.
 */
class RetrySettingsTest {

    private static Binder application() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    @Test
    void ticketListenerRetriesFiveTimesWithinAboutFifteenSeconds() throws Exception {
        RabbitProperties.ListenerRetry retry = application()
                .bind("spring.rabbitmq.listener.simple.retry", RabbitProperties.ListenerRetry.class).get();

        assertThat(retry.isEnabled()).isTrue();
        assertThat(retry.getMaxRetries()).isEqualTo(4); // 1 try + 4 retries = 5 attempts
        assertThat(retry.getInitialInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(retry.getMultiplier()).isEqualTo(2.0);
        assertThat(retry.getMaxInterval()).isEqualTo(Duration.ofSeconds(8));

        long totalWaitMillis = 0;
        long wait = retry.getInitialInterval().toMillis();
        for (long i = 0; i < retry.getMaxRetries(); i++) {
            totalWaitMillis += Math.min(wait, retry.getMaxInterval().toMillis());
            wait = (long) (wait * retry.getMultiplier());
        }
        assertThat(totalWaitMillis).isEqualTo(15_000);
    }

    @Test
    void theRegistryIsFetchedEveryFiveSeconds() throws Exception {
        assertThat(application().bind("eureka.client.registry-fetch-interval-seconds", Integer.class).get())
                .isEqualTo(5);
    }
}
