package dev.surge.notification.config;

import dev.surge.common.security.InternalAudiences;
import dev.surge.common.security.InternalTokenSigner;
import dev.surge.common.security.InternalTokenSigningInterceptor;
import dev.surge.common.security.SurgeEnv;
import dev.surge.notification.booking.BookingClient;
import dev.surge.notification.catalog.CatalogClient;
import dev.surge.notification.user.UserDirectory;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Service-to-service clients (booking, auth, catalog), each signed as {@code svc:notification} for its one target.
 * Hostnames are Eureka service names, resolved by the load balancer.
 */
@Configuration(proxyBeanMethods = false)
public class ClientsConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Boot's own builder backs off as soon as another {@code RestClient.Builder} bean exists, so the default one is
     * re-declared here (same configurer, so Boot's customizers apply) and made primary.
     */
    @Bean
    @Primary
    @Scope("prototype")
    RestClient.Builder restClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    @Bean
    @LoadBalanced
    RestClient.Builder loadBalancedRestClientBuilder(RestClientBuilderConfigurer configurer) {
        return configurer.configure(RestClient.builder());
    }

    @Bean
    CatalogClient catalogClient(@LoadBalanced RestClient.Builder loadBalanced, InternalTokenSigner signer,
                                @Value("${surge.clients.catalog-base-url:http://catalog-service}") String baseUrl,
                                Environment environment) {
        return new CatalogClient(restClient(loadBalanced, baseUrl, READ_TIMEOUT, signer, InternalAudiences.CATALOG,
                SurgeEnv.from(environment)));
    }

    @Bean
    BookingClient bookingClient(@LoadBalanced RestClient.Builder loadBalanced, InternalTokenSigner signer,
                                @Value("${surge.clients.booking-base-url:http://booking-service}") String baseUrl,
                                Environment environment) {
        return new BookingClient(restClient(loadBalanced, baseUrl, READ_TIMEOUT, signer, InternalAudiences.BOOKING,
                SurgeEnv.from(environment)));
    }

    @Bean
    UserDirectory userDirectory(@LoadBalanced RestClient.Builder loadBalanced, InternalTokenSigner signer,
                                @Value("${surge.clients.auth-base-url:http://auth-service}") String baseUrl,
                                Environment environment) {
        return new UserDirectory(restClient(loadBalanced, baseUrl, READ_TIMEOUT, signer, InternalAudiences.AUTH,
                SurgeEnv.from(environment)));
    }

    /**
     * Clones the builder (the shared one is never mutated) and applies the base URL, the timeouts, and notification's
     * signing interceptor: every HTTP attempt carries a fresh {@code svc:notification} token addressed to
     * {@code service}. The audience is decided here, once, from the client's target and its configured base URL
     * ({@link InternalAudiences#forClient}: a base URL naming another service fails startup, and in prod the host must
     * be the target's own name); no request can change it.
     */
    public static RestClient restClient(RestClient.Builder builder, String baseUrl, Duration readTimeout,
                                        InternalTokenSigner signer, String service, SurgeEnv env) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        String audience = InternalAudiences.forClient(service, baseUrl, env);
        return builder.clone().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor(new InternalTokenSigningInterceptor(signer, audience)).build();
    }
}
