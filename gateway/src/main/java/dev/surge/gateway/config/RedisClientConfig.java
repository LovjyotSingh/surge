package dev.surge.gateway.config;

import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lettuce emits Redis replies on its scheduler, not on the connection's I/O thread.
 *
 * <p>Spring Data Redis gives the gateway one shared Lettuce connection, and by default Lettuce emits every reactive
 * reply on that connection's single I/O thread. Everything downstream of a reply then runs on that thread: after the
 * token denylist's {@code EXISTS} come the rest of the security chain, the internal identity token's ES256 signature,
 * the rate limiter and the routing filters. Under a burst (the 10,000-buyer load test releases every buyer at once)
 * that one thread was busy signing tokens while thousands of other replies waited behind it, so the denylist check
 * and the limiter passed their then 1 s timeouts and the gateway answered 503 to almost every request. A thread dump showed
 * {@code lettuce-nioEventLoop} inside {@code ECDSASigner.sign}; Redis itself had spent under half a second of CPU.
 *
 * <p>With {@code publishOnScheduler} the replies are emitted on Lettuce's computation pool (one thread per CPU), so
 * the I/O thread only reads and writes Redis. Boot's own options (the 1 s connect and 3 s command timeouts) are kept:
 * this customizes the builder Boot creates.
 */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {

    @Bean
    LettuceClientOptionsBuilderCustomizer publishRepliesOffTheIoThread() {
        return options -> options.publishOnScheduler(true);
    }
}
