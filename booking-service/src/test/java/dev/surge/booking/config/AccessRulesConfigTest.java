package dev.surge.booking.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.common.security.IdentityTokens;
import dev.surge.common.security.InternalSecurityAutoConfiguration;
import dev.surge.common.security.SurgeEnv;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * booking's rules on their own (no database, no brokers): the API docs and Swagger UI are open in dev and denied, to
 * every caller, anywhere else. The shared test context runs in dev, so prod is exercised here with a PROD
 * {@link SurgeEnv} bean (the keys stay the committed dev ones, as the property says dev).
 */
class AccessRulesConfigTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InternalSecurityAutoConfiguration.class,
                    SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class))
            .withUserConfiguration(Web.class, AccessRulesConfig.class)
            .withPropertyValues("spring.application.name=booking-service", "surge.internal-identity.enforce=true");

    private static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void docsAreOpenInDev() {
        runner.withBean(SurgeEnv.class, () -> SurgeEnv.DEV).run(context -> {
            MockMvc mvc = mvc(context);
            for (String path : new String[] {"/api/bookings/v3/api-docs", "/api/bookings/v3/api-docs/swagger-config",
                    "/api/bookings/docs", "/swagger-ui/index.html"}) {
                mvc.perform(get(path)).andExpect(status().isOk());
            }
        });
    }

    @Test
    void docsAreDeniedOutsideDevWhoeverAsks() {
        runner.withBean(SurgeEnv.class, () -> SurgeEnv.PROD).run(context -> {
            MockMvc mvc = mvc(context);
            for (String path : new String[] {"/api/bookings/v3/api-docs", "/api/bookings/v3/api-docs/swagger-config",
                    "/api/bookings/docs", "/swagger-ui/index.html"}) {
                mvc.perform(get(path)).andExpect(status().isUnauthorized());
                mvc.perform(get(path).with(IdentityTokens.asAdmin("booking-service", "u-admin")))
                        .andExpect(status().isForbidden());
                mvc.perform(get(path).with(IdentityTokens.asAnonymous("booking-service")))
                        .andExpect(status().isUnauthorized());
            }
            // the API itself is unaffected
            mvc.perform(get("/api/bookings/events/probe").with(IdentityTokens.asAnonymous("booking-service")))
                    .andExpect(status().isOk());
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(Docs.class)
    static class Web {
    }

    /** Stand-ins for springdoc's endpoints, so "allowed" shows as a 200. */
    @RestController
    static class Docs {
        @GetMapping({"/api/bookings/v3/api-docs", "/api/bookings/v3/api-docs/swagger-config", "/api/bookings/docs",
            "/swagger-ui/index.html", "/api/bookings/events/probe"})
        String docs() {
            return "{}";
        }
    }
}
