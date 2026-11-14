package dev.surge.booking.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The HTTP payment client, active only for surge.payment.mode=http (compose). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "surge.payment.mode", havingValue = "http")
public class PaymentClientConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Boot's own builder backs off as soon as another {@code RestClient.Builder} bean exists, so the default one is
     * re-declared here (same configurer, so Boot's customizers, metrics and tracing apply) and made primary.
     */
    @Bean
    @Primary
    @Scope("prototype")
    RestClient.Builder restClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    /** Resolves {@code http://payment-service} through Eureka. Spring Cloud adds the load-balancer interceptor to it. */
    @Bean
    @LoadBalanced
    RestClient.Builder paymentRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    @Bean
    HttpPaymentGateway paymentGateway(@LoadBalanced RestClient.Builder loadBalanced,
                                      @Value("${surge.payment.base-url:http://payment-service}") String baseUrl,
                                      CircuitBreakerRegistry breakers, RetryRegistry retries) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(READ_TIMEOUT);
        // clone(): the shared builder is never mutated (the load-balancer customizer owns its interceptors).
        RestClient client = loadBalanced.clone().baseUrl(baseUrl).requestFactory(factory).build();
        return new HttpPaymentGateway(client, breakers.circuitBreaker("payment"), retries.retry("payment"));
    }
}
