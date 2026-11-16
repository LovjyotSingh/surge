package dev.surge.auth.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * One shared Spring context for every integration test (never add field-level mocks or test properties: each
 * variation would start a second context with a new container). Test-token minting is on so it can be exercised;
 * the disabled case is covered without a Boot context (see TestTokenDisabledTest).
 *
 * <p>The PasswordEncoder is a Mockito spy (delegating to the real BCrypt encoder) so tests can prove that a throttled
 * sign-in never checks a password; Mockito resets it after each test. Mailpit catches the verification emails.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {"surge.auth.test-tokens.enabled=true", "eureka.client.enabled=false",
        "spring.cloud.config.enabled=false"})
@AutoConfigureMockMvc
@MockitoSpyBean(types = PasswordEncoder.class)
@Import(TestcontainersConfig.class)
public @interface IntegrationTest {
}
