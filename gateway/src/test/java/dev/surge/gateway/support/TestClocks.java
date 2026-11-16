package dev.surge.gateway.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The clock of the JWKS cache's cooldown ({@code SecurityConfig.jwksCache} picks up a bean named {@code jwksClock}).
 * Tests advance it past the 30 s cooldown instead of sleeping; nothing else in the gateway reads it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClocks {

    @Bean
    MutableClock jwksClock() {
        return new MutableClock();
    }
}
