package dev.surge.notification.config;

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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Service-to-service clients (catalog, auth). Hostnames are Eureka service names, resolved by the load balancer. */
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
    CatalogClient catalogClient(@LoadBalanced RestClient.Builder loadBalanced,
                                @Value("${surge.clients.catalog-base-url:http://catalog-service}") String baseUrl) {
        return new CatalogClient(restClient(loadBalanced, baseUrl, READ_TIMEOUT));
    }

    @Bean
    UserDirectory userDirectory(@LoadBalanced RestClient.Builder loadBalanced,
                                @Value("${surge.clients.auth-base-url:http://auth-service}") String baseUrl) {
        return new UserDirectory(restClient(loadBalanced, baseUrl, READ_TIMEOUT));
    }

    /** Clones the builder (the shared one is never mutated) and applies the base URL and timeouts. */
    public static RestClient restClient(RestClient.Builder builder, String baseUrl, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
