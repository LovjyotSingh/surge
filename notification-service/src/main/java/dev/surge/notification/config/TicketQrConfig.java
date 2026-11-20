package dev.surge.notification.config;

import dev.surge.common.security.SurgeEnv;
import dev.surge.notification.ticket.TicketQr;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The ticket QR signer and verifier (SEC-20), keyed by {@code surge.ticket.qr-key} (env {@code SURGE_TICKET_QR_KEY}).
 * Startup fails in prod without a real key ({@link TicketQr#fromConfig}).
 */
@Configuration(proxyBeanMethods = false)
public class TicketQrConfig {

    @Bean
    TicketQr ticketQr(@Value("${surge.ticket.qr-key:}") String key, Environment environment) {
        return TicketQr.fromConfig(key, SurgeEnv.from(environment));
    }
}
