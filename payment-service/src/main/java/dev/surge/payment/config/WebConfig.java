package dev.surge.payment.config;

import dev.surge.common.security.AdminAudit;
import dev.surge.common.security.CurrentIdentity;
import dev.surge.common.security.InternalTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The caller checks again before argument binding, behind the identity chain's own rules ({@code AccessRulesConfig}):
 * the admin routes need a gateway ADMIN (a refusal is audit-logged), the payments API needs {@code svc:booking}. So a
 * caller that may not use an endpoint never gets a 400 that describes its body or parameters, even if the chain's
 * rules were ever loosened. The controllers check a third time.
 */
@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAccess()).addPathPatterns("/api/payments/admin", "/api/payments/admin/**");
        registry.addInterceptor(bookingAccess()).addPathPatterns("/api/payments", "/api/payments/**")
                .excludePathPatterns("/api/payments/admin", "/api/payments/admin/**");
    }

    static HandlerInterceptor adminAccess() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                AdminAudit.requireAdmin("payment.admin");
                return true;
            }
        };
    }

    static HandlerInterceptor bookingAccess() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                CurrentIdentity.requireService(InternalTokens.SVC_BOOKING);
                return true;
            }
        };
    }
}
