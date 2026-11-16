package dev.surge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SEC-15: without credentials any container could read every service's configuration. Everything but health needs
 * HTTP Basic with {@code CONFIG_USER} / {@code CONFIG_PASSWORD} ({@link BasicCredentials}); clients send them as
 * {@code spring.cloud.config.username} / {@code password}. Clients only read (GET), so CSRF stays on for anything
 * else. No sessions: every request authenticates.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class ConfigServerSecurity {

    /** The development credentials; every service's application.yml uses the same ones by default. */
    static final BasicCredentials DEVELOPMENT = new BasicCredentials("surge-dev", "dev-only-config-password");

    @Bean
    SecurityFilterChain configServerSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    UserDetailsService configServerUsers(Environment environment) {
        BasicCredentials credentials = BasicCredentials.resolve(environment, "surge.config-server", "CONFIG_USER",
                "CONFIG_PASSWORD", DEVELOPMENT);
        return new InMemoryUserDetailsManager(User.withUsername(credentials.username())
                .password(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(credentials.password()))
                .roles("CONFIG_CLIENT")
                .build());
    }
}
