package dev.surge.gateway.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;

/**
 * One shared Spring context for every gateway test: the same annotation on {@link GatewayTestBase}, never any
 * field-level mock or per-class property (each variation would start a second context and a second Redis). The
 * downstream mock servers and the JWKS endpoint are static in the base class and wired by ONE
 * {@code @DynamicPropertySource}. The global response timeout is shortened to 1.5 s so the SSE test proves the
 * stream route is exempt from it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"eureka.client.enabled=false",
        "spring.cloud.config.enabled=false",
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1500ms"})
@AutoConfigureWebTestClient
@Import({TestcontainersConfig.class, TestClocks.class})
public @interface IntegrationTest {
}
