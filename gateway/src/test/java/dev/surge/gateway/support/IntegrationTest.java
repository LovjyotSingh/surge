package dev.surge.gateway.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * One shared Spring context for every gateway test: the same annotation on {@link GatewayTestBase}, never any
 * field-level mock or per-class property (each variation would start a second context and a second Redis). The
 * downstream mock servers and the JWKS endpoint are static in the base class and wired by ONE
 * {@code @DynamicPropertySource}. The global response timeout is shortened to 1.5 s so the SSE test proves the
 * stream route is exempt from it.
 *
 * <p>The test client connects over loopback, which is the trusted proxy here (as nginx is in compose), so each test
 * picks its client address with {@code X-Forwarded-For} ({@link GatewayTestBase#clientIp}). The rate limiter's
 * {@link ReactiveStringRedisTemplate} is a Mockito spy delegating to the real one, so a test can make Redis fail;
 * Mockito resets it after each test. The token denylist reads the same template, but only through {@code hasKey} and
 * {@code opsForValue()}, so stubbing the limiter's script ({@code execute}) leaves logout and revocation working.
 *
 * <p>{@link TestClocks} adds the JWKS cache's {@code jwksClock} ({@link MutableClock}); nothing else in the gateway
 * takes a {@code Clock}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"eureka.client.enabled=false",
        "spring.cloud.config.enabled=false",
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1500ms",
        "surge.security.trusted-proxies=127[.]0[.]0[.]1|0:0:0:0:0:0:0:1"})
@AutoConfigureWebTestClient
@MockitoSpyBean(types = ReactiveStringRedisTemplate.class)
@Import({TestcontainersConfig.class, TestClocks.class})
public @interface IntegrationTest {
}
