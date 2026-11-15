package dev.surge.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.common.web.RequestIdFilter;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** The servlet chain from common, wired the way a service gets it: Boot's own security auto-configuration included. */
class InternalSecurityAutoConfigurationTest {

    private static final String PROBLEM = "https://surge.dev/problems/";
    private static final String HEADER = "X-Surge-Identity";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InternalSecurityAutoConfiguration.class,
                    InternalIdentitySignerAutoConfiguration.class, SecurityAutoConfiguration.class,
                    ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
                    UserDetailsServiceAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class))
            .withUserConfiguration(TestWeb.class)
            .withPropertyValues("spring.application.name=booking-service");

    private final WebApplicationContextRunner enforcing = runner.withPropertyValues(
            "surge.internal-identity.enforce=true", "surge.internal-identity.issuer=gateway");

    /** A service that receives service-to-service calls opts in to those issuers (S3 does this per service). */
    private final WebApplicationContextRunner enforcingWithServices = enforcing.withPropertyValues(
            "surge.internal-identity.allowed-issuers=gateway,svc:booking,svc:notification");

    private static InternalTokenSigner devSigner(String issuer) {
        return new InternalTokenSigner(InternalKeys.loadSigningKey(null, issuer, SurgeEnv.DEV,
                new DefaultResourceLoader()), issuer);
    }

    private static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new RequestIdFilter(), context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    // ---- off by default ------------------------------------------------------------------------------------------

    @Test
    void offByDefault() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InternalSecurityAutoConfiguration.class,
                        InternalIdentitySignerAutoConfiguration.class))
                .withUserConfiguration(TestWeb.class)
                .withPropertyValues("spring.application.name=booking-service")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
                    assertThat(context).doesNotHaveBean(InternalTokenVerifier.class);
                    assertThat(context).doesNotHaveBean(InternalTokenSigner.class);
                    // the service behaves exactly as before: no filter chain, no token needed
                    MockMvcBuilders.webAppContextSetup(context).build().perform(get("/hello"))
                            .andExpect(status().isOk()).andExpect(content().string("none"));
                });
    }

    // ---- on ------------------------------------------------------------------------------------------------------

    @Test
    void whenOnEveryEndpointNeedsAValidIdentityToken() {
        enforcing.run(context -> {
            assertThat(context).hasNotFailed();
            MockMvc mvc = mvc(context);
            InternalTokenSigner gateway = context.getBean(InternalTokenSigner.class);

            // no token: 401 problem in the services' shape
            mvc.perform(get("/hello")).andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"))
                    .andExpect(jsonPath("$.title").value("Unauthorized"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.instance").value("/hello"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());

            // forged legacy headers, a token in Authorization, garbage, a token for another service: all 401
            String valid = gateway.sign("booking-service", "u-1", List.of("USER"), "r-1");
            mvc.perform(get("/hello").header("X-User-Id", "u-1").header("X-User-Roles", "ADMIN"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/hello").header("Authorization", "Bearer " + valid)).andExpect(status().isUnauthorized());
            mvc.perform(get("/hello").header(HEADER, "garbage")).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
            mvc.perform(get("/hello").header(HEADER, "Bearer " + valid)).andExpect(status().isUnauthorized());
            mvc.perform(get("/hello").header(HEADER, valid, valid)).andExpect(status().isUnauthorized());
            mvc.perform(get("/hello").header(HEADER, gateway.sign("catalog-service", "u-1", List.of("USER"), null)))
                    .andExpect(status().isUnauthorized());

            // valid: the identity reaches the controller, roles mapped
            mvc.perform(get("/hello").header(HEADER, gateway.sign("booking-service", "u-1", List.of("USER", "ADMIN"),
                    "r-1"))).andExpect(status().isOk()).andExpect(content().string("gateway|u-1|[ADMIN, USER]"));
            // anonymous (public route through the gateway): accepted, no subject
            mvc.perform(get("/hello").header(HEADER, gateway.sign("booking-service", null, List.of(), null)))
                    .andExpect(status().isOk()).andExpect(content().string("gateway|null|[]"));

            // by default only the gateway is trusted: a validly signed service token is still 401
            mvc.perform(get("/hello").header(HEADER, devSigner("svc:notification").signForService("booking-service",
                    null))).andExpect(status().isUnauthorized());
        });
    }

    @Test
    void healthIsOpen() {
        enforcing.run(context -> mvc(context).perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(content().string("UP")));
    }

    @Test
    void serviceRulesComeFromSurgeAccessRulesBeans() {
        enforcingWithServices.withUserConfiguration(Rules.class).run(context -> {
            MockMvc mvc = mvc(context);
            InternalTokenSigner gateway = context.getBean(InternalTokenSigner.class);
            InternalTokenSigner booking = devSigner("svc:booking");
            InternalTokenSigner notification = devSigner("svc:notification");

            mvc.perform(get("/admin/x").header(HEADER, gateway.sign("booking-service", "u-1", List.of("USER"), null)))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
            mvc.perform(get("/admin/x").header(HEADER, gateway.sign("booking-service", "u-9", List.of("USER", "ADMIN"),
                    null))).andExpect(status().isOk()).andExpect(content().string("admin:u-9"));
            // no user at all: 401 missing-user, not 403
            mvc.perform(get("/admin/x").header(HEADER, gateway.sign("booking-service", null, List.of(), null)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
            mvc.perform(get("/admin/x")).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "unauthorized"));
            // a service is never an admin (it cannot even carry a role)
            mvc.perform(get("/admin/x").header(HEADER, booking.signForService("booking-service", null)))
                    .andExpect(status().isForbidden());

            mvc.perform(get("/internal/x").header(HEADER, notification.signForService("booking-service", null)))
                    .andExpect(status().isOk());
            mvc.perform(get("/internal/x").header(HEADER, booking.signForService("booking-service", null)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/internal/x").header(HEADER, gateway.sign("booking-service", "u-1", List.of("ADMIN"),
                    null))).andExpect(status().isForbidden());
        });
    }

    @Test
    void currentIdentityHelpersAnswerLikeTheServicesDoToday() {
        enforcingWithServices.run(context -> {
            MockMvc mvc = mvc(context);
            InternalTokenSigner gateway = context.getBean(InternalTokenSigner.class);
            String notification = devSigner("svc:notification").signForService("booking-service", null);

            // a service identity is never a user
            mvc.perform(get("/me").header(HEADER, notification)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"));
            mvc.perform(get("/me").header(HEADER, gateway.sign("booking-service", "u-5", List.of("USER"), null)))
                    .andExpect(status().isOk()).andExpect(content().string("u-5"));
            mvc.perform(get("/me").header(HEADER, gateway.sign("booking-service", null, List.of(), null)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value(PROBLEM + "missing-user"))
                    .andExpect(jsonPath("$.requestId").isNotEmpty());
            mvc.perform(get("/in-service-admin").header(HEADER, gateway.sign("booking-service", "u-5",
                    List.of("USER", "SUPERADMIN", "ADMINS"), null)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value(PROBLEM + "forbidden"));
            mvc.perform(get("/in-service-admin").header(HEADER, notification)).andExpect(status().isForbidden());
            mvc.perform(get("/in-service-admin").header(HEADER, gateway.sign("booking-service", "u-5",
                    List.of("ADMIN"), null))).andExpect(status().isOk());

            // authorities: ROLE_* for a gateway user, nothing for a service
            mvc.perform(get("/authorities").header(HEADER, gateway.sign("booking-service", "u-5",
                    List.of("USER", "ADMIN"), null))).andExpect(content().string("[ROLE_ADMIN, ROLE_USER]"));
            mvc.perform(get("/authorities").header(HEADER, notification)).andExpect(content().string("[]"));
        });
    }

    @Test
    void serviceTokensNeverGetRoleAuthoritiesEvenIfTheyCarriedRoles() {
        // the verifier already refuses such a token; the converter is the second lock
        var converter = new InternalIdentityAuthenticationConverter("booking-service");
        java.util.function.Function<String, org.springframework.security.oauth2.jwt.Jwt> jwt = issuer ->
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "ES256")
                        .claim("iss", issuer).subject("u-1").claim("roles", List.of("USER", "ADMIN")).build();

        var service = converter.convert(jwt.apply("svc:booking"));
        assertThat(service.getAuthorities()).isEmpty();
        assertThat(((InternalIdentity) service.getDetails()).isUser()).isFalse();
        assertThat(converter.convert(jwt.apply("gateway")).getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");

        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        try {
            context.setAuthentication(service);
            assertThatThrownBy(CurrentIdentity::requireUser).isInstanceOfSatisfying(InternalIdentityException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
            assertThatThrownBy(CurrentIdentity::requireAdmin).isInstanceOfSatisfying(InternalIdentityException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void asyncDispatchKeepsTheIdentity() {
        enforcing.run(context -> {
            MockMvc mvc = mvc(context);
            String token = context.getBean(InternalTokenSigner.class).sign("booking-service", "u-7", List.of("USER"),
                    null);
            MvcResult started = mvc.perform(get("/async").header(HEADER, token))
                    .andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andExpect(content().string("u-7"));
        });
    }

    @Test
    void bootsDefaultSecurityBacksOff() {
        enforcing.run(context -> {
            assertThat(context).getBeans(SecurityFilterChain.class).hasSize(1).containsKey(
                    "internalIdentitySecurityFilterChain");
            assertThat(context).doesNotHaveBean(UserDetailsService.class);
            assertThat(context).getBean(JwtDecoder.class).isInstanceOf(InternalJwtDecoder.class);
        });
    }

    // ---- startup refusals ----------------------------------------------------------------------------------------

    @Test
    void prodWithUnsetLocationsFails() {
        enforcing.withPropertyValues("surge.env=prod").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("surge.env=prod", "surge.internal-identity.enforce=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("jwks-location");
        });
    }

    @Test
    void prodWithDevKeysFails() {
        runner.withPropertyValues("surge.env=prod", "surge.internal-identity.enforce=true",
                "surge.internal-identity.jwks-location=classpath:surge-dev-keys/internal-jwks.json").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("development");
                });
    }

    @Test
    void anUnknownSurgeEnvOrAMissingApplicationNameFails() {
        enforcing.withPropertyValues("surge.env=production").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessage("SURGE_ENV must be exactly 'dev' or 'prod'");
        });
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InternalSecurityAutoConfiguration.class))
                .withUserConfiguration(TestWeb.class)
                .withPropertyValues("surge.internal-identity.enforce=true")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("spring.application.name"));
    }

    @Test
    void enforceWithoutTheSecurityStarterFailsInsteadOfRunningOpen() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(dev.surge.common.CommonAutoConfiguration.class,
                        InternalSecurityAutoConfiguration.class))
                .withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        SecurityFilterChain.class))
                .withUserConfiguration(TestWeb.class)
                .withPropertyValues("spring.application.name=booking-service", "surge.internal-identity.enforce=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("spring-boot-starter-oauth2-resource-server");
                });
    }

    // ---- the signer on its own (no web application: the reactive gateway uses it this way) -------------------------

    @Test
    void signerIsAvailableWithoutAServletOrSecurityStack() {
        var plain = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InternalIdentitySignerAutoConfiguration.class,
                        InternalSecurityAutoConfiguration.class));
        plain.run(context -> assertThat(context).doesNotHaveBean(InternalTokenSigner.class));
        plain.withPropertyValues("surge.internal-identity.issuer=gateway").run(context -> {
            assertThat(context).hasSingleBean(InternalTokenSigner.class);
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
            assertThat(context.getBean(InternalTokenSigner.class).issuer()).isEqualTo("gateway");
        });
        plain.withPropertyValues("surge.internal-identity.issuer=gateway", "surge.env=prod")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("signing-key-location"));
        plain.withPropertyValues("surge.internal-identity.issuer=gateway", "surge.env=prod",
                "surge.internal-identity.signing-key-location=classpath:surge-dev-keys/gateway.jwk")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("development"));
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({TestController.class, TestAdvice.class})
    static class TestWeb {
    }

    @Configuration(proxyBeanMethods = false)
    static class Rules {
        @Bean
        SurgeAccessRules testRules() {
            return rules -> rules
                    .requestMatchers("/admin/**").access(InternalAccess.gatewayAdmin())
                    .requestMatchers("/internal/**").access(InternalAccess.service("svc:notification"));
        }
    }

    @RestController
    static class TestController {
        @GetMapping("/hello")
        String hello() {
            return CurrentIdentity.get()
                    .map(i -> i.issuer() + "|" + i.subject() + "|" + new TreeSet<>(i.roles()))
                    .orElse("none");
        }

        @GetMapping("/me")
        String me() {
            return CurrentIdentity.requireUser();
        }

        @GetMapping("/admin/x")
        String admin() {
            return "admin:" + CurrentIdentity.requireAdmin().subject();
        }

        @GetMapping("/in-service-admin")
        String inServiceAdmin() {
            return "admin:" + CurrentIdentity.requireAdmin().subject();
        }

        @GetMapping("/internal/x")
        String internal() {
            return "internal";
        }

        @GetMapping("/authorities")
        String authorities() {
            return new TreeSet<>(org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .getAuthentication().getAuthorities().stream().map(Object::toString).toList()).toString();
        }

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }

        @GetMapping("/async")
        Callable<String> async() {
            return CurrentIdentity::requireUser;
        }
    }

    /** What every service's ApiExceptionHandler is: a ResponseEntityExceptionHandler. */
    @RestControllerAdvice
    static class TestAdvice extends ResponseEntityExceptionHandler {
    }
}
