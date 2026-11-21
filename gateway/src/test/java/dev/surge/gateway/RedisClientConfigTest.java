package dev.surge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.gateway.security.TokenDenylist;
import dev.surge.gateway.support.GatewayTestBase;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * The gateway's Lettuce client emits Redis replies off the connection's I/O thread ({@code RedisClientConfig}), and
 * Boot's timeouts survive the customization.
 */
class RedisClientConfigTest extends GatewayTestBase {

    @Autowired
    LettuceConnectionFactory connectionFactory;

    @Autowired
    ReactiveStringRedisTemplate redis;

    @Autowired
    TokenDenylist denylist;

    @Test
    void repliesArePublishedOnLettucesSchedulerAndTimeoutsAreKept() {
        ClientOptions options = connectionFactory.getClientConfiguration().getClientOptions().orElseThrow();

        assertThat(options.isPublishOnScheduler()).isTrue();
        TimeoutOptions timeouts = options.getTimeoutOptions();
        assertThat(timeouts.isTimeoutCommands()).isTrue();
        assertThat(connectionFactory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(3));
        // one definition: the denylist's deadline is the same spring.data.redis.timeout, never a second constant
        assertThat(denylist.timeout()).isEqualTo(connectionFactory.getClientConfiguration().getCommandTimeout());
        assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void replyCallbacksDoNotRunOnTheConnectionsIoThread() {
        String key = "surge:test:publish-on:" + System.nanoTime();
        StepVerifier.create(Flux.range(0, 50)
                        .flatMap(i -> redis.hasKey(key).map(found -> Thread.currentThread().getName()))
                        .subscribeOn(Schedulers.parallel())
                        .distinct())
                .thenConsumeWhile(thread -> {
                    assertThat(thread).doesNotContain("nioEventLoop").doesNotContain("epollEventLoop");
                    return true;
                })
                .verifyComplete();
    }
}
