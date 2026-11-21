package dev.surge.notification.config;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * S10: the ticket email links to {@code surge.notification.public-base-url} (env {@code PUBLIC_BASE_URL}), where the
 * reader signs in. On a public deployment ({@code SURGE_PUBLIC_DEPLOYMENT=true}) it must be an {@code https://} URL
 * with a host: a plain-HTTP link sends the reader to a page where they type their password, protected only by a
 * redirect a network attacker can strip. Private runs keep their local {@code http://localhost:8080} link.
 */
@Configuration(proxyBeanMethods = false)
class PublicBaseUrlGuard {

    PublicBaseUrlGuard(@Value("${surge.security.public-deployment:false}") boolean publicDeployment,
            @Value("${surge.notification.public-base-url:}") String publicBaseUrl) {
        if (publicDeployment && !isHttpsWithHost(publicBaseUrl)) {
            throw new IllegalStateException("PUBLIC_BASE_URL must be an https:// URL (e.g. https://surge.example.com) "
                    + "when SURGE_PUBLIC_DEPLOYMENT=true");
        }
    }

    private static boolean isHttpsWithHost(String url) {
        try {
            URI uri = URI.create(url.strip());
            return uri.getScheme() != null && "https".equals(uri.getScheme().toLowerCase(Locale.ROOT))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
