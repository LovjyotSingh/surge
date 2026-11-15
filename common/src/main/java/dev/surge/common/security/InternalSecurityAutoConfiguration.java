package dev.surge.common.security;

import jakarta.servlet.DispatcherType;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The zero-trust chain every servlet service gets once it adds the security starters and sets
 * {@code surge.internal-identity.enforce=true}: stateless, no CSRF/session/form/basic login, and every request needs
 * a verified {@code X-Surge-Identity} (the gateway sends an anonymous one for public routes). {@code Authorization}
 * and {@code X-User-*} are never read. Rules:
 * <ol>
 *   <li>{@code /actuator/health/**} open, and error dispatches open (they only render an already-decided error);</li>
 *   <li>every {@link SurgeAccessRules} bean, in order;</li>
 *   <li>anything else: any valid identity.</li>
 * </ol>
 * Ordered before Boot's security auto-configuration, which then backs off (no default chain, no generated password).
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({SecurityFilterChain.class, HttpSecurity.class, JwtDecoder.class, BearerTokenResolver.class})
@ConditionalOnProperty(prefix = "surge.internal-identity", name = "enforce", havingValue = "true")
@EnableConfigurationProperties(InternalIdentityProperties.class)
@EnableWebSecurity
public class InternalSecurityAutoConfiguration {

    @Bean
    InternalTokenVerifier internalTokenVerifier(InternalIdentityProperties properties, Environment environment,
            ResourceLoader resourceLoader, ObjectProvider<Clock> clock) {
        String audience = environment.getProperty("spring.application.name");
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("spring.application.name must be set: internal identity tokens are "
                    + "checked against it as their audience");
        }
        SurgeEnv env = SurgeEnv.from(environment);
        return new InternalTokenVerifier(
                InternalKeys.loadVerificationKeys(properties.getJwksLocation(), env, resourceLoader),
                properties.getAllowedIssuers(), audience, clock.getIfUnique(Clock::systemUTC));
    }

    /** Also a bean so Boot's resource-server and in-memory-user auto-configuration see a decoder and back off. */
    @Bean
    InternalJwtDecoder internalIdentityJwtDecoder(InternalTokenVerifier verifier) {
        return new InternalJwtDecoder(verifier);
    }

    @Bean
    SecurityFilterChain internalIdentitySecurityFilterChain(HttpSecurity http, InternalJwtDecoder decoder,
            ObjectProvider<SurgeAccessRules> accessRules) throws Exception {
        InternalSecurityProblemHandlers problems = new InternalSecurityProblemHandlers();
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(new InternalIdentityTokenResolver())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(
                                new InternalIdentityAuthenticationConverter(decoder.verifier().audience()))))
                .authorizeHttpRequests(rules -> {
                    rules.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    rules.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    accessRules.orderedStream().forEach(r -> r.configure(rules));
                    rules.anyRequest().authenticated();
                })
                .build();
    }
}
