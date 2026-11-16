package dev.surge.discovery;

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
 * SEC-04: without credentials anyone on the network could register an instance as {@code BOOKING-SERVICE} or
 * {@code PAYMENT-SERVICE} and receive the gateway's traffic. Everything but health needs HTTP Basic with
 * {@code EUREKA_USER} / {@code EUREKA_PASSWORD} ({@link BasicCredentials}); clients put them in their
 * {@code defaultZone} URL. Eureka's REST clients cannot send CSRF tokens, so CSRF is off for {@code /eureka/**}
 * (Basic credentials are not sent by a browser on its own). No sessions: every request authenticates.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class DiscoverySecurity {

    /** The development credentials; every service's application.yml uses the same ones by default. */
    static final BasicCredentials DEVELOPMENT = new BasicCredentials("surge-dev", "dev-only-eureka-password");

    @Bean
    SecurityFilterChain discoverySecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/eureka/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    UserDetailsService discoveryUsers(Environment environment) {
        BasicCredentials credentials = BasicCredentials.resolve(environment, "surge.discovery", "EUREKA_USER",
                "EUREKA_PASSWORD", DEVELOPMENT);
        return new InMemoryUserDetailsManager(User.withUsername(credentials.username())
                .password(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(credentials.password()))
                .roles("EUREKA_CLIENT")
                .build());
    }
}
