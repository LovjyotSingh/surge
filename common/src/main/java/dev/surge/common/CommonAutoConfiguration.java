package dev.surge.common;

import dev.surge.common.web.RequestIdFilter;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonAutoConfiguration {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * {@code surge.internal-identity.enforce=true} without Spring Security on the classpath would silently skip the
     * internal identity chain and leave every endpoint open; refuse to start instead.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "surge.internal-identity", name = "enforce", havingValue = "true")
    static class InternalIdentityEnforcementGuard {

        private static final List<String> REQUIRED = List.of(
                "org.springframework.security.web.SecurityFilterChain",
                "org.springframework.security.config.annotation.web.builders.HttpSecurity",
                "org.springframework.security.oauth2.jwt.JwtDecoder",
                "org.springframework.security.oauth2.server.resource.web.BearerTokenResolver");

        InternalIdentityEnforcementGuard(ApplicationContext context) {
            for (String type : REQUIRED) {
                if (!ClassUtils.isPresent(type, context.getClassLoader())) {
                    throw new IllegalStateException("surge.internal-identity.enforce=true needs "
                            + "spring-boot-starter-oauth2-resource-server; without it no request would be checked");
                }
            }
        }
    }
}
