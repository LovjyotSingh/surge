package dev.surge.common.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * SEC-16 (S10): on a public deployment ({@code surge.security.public-deployment=true}, env
 * {@code SURGE_PUBLIC_DEPLOYMENT}, set by compose.prod.yaml) a service that sends email refuses to start unless it
 * talks to a real SMTP provider the safe way:
 *
 * <ul>
 *   <li>a host ({@code MAIL_HOST});</li>
 *   <li>SMTP AUTH ({@code MAIL_SMTP_AUTH=true}) with a user name and password ({@code MAIL_USERNAME}, and
 *       {@code MAIL_PASSWORD} from a secret file);</li>
 *   <li>STARTTLS enabled AND required ({@code MAIL_STARTTLS=true}), so a stripped STARTTLS offer fails the send instead
 *       of sending the password, the codes and the tickets in clear text;</li>
 *   <li>a From address outside the local {@code surge.local} domain ({@code MAIL_FROM}), which no provider would
 *       accept and no SPF, DKIM or DMARC record could vouch for.</li>
 * </ul>
 *
 * Local and private runs (Mailpit: no auth, no TLS) are never checked. Which services send mail is listed here once,
 * by {@code spring.application.name}. Messages name variables, never values.
 */
public final class MailSettings {

    /** auth-service sends verification codes, notification-service sends tickets. */
    static final Set<String> SENDERS = Set.of("auth-service", "notification-service");

    private MailSettings() {
    }

    /** Refuses to start while {@link #problems} lists anything. */
    public static void check(Environment environment) {
        List<String> problems = problems(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("SURGE_PUBLIC_DEPLOYMENT=true needs a real SMTP provider: "
                    + String.join("; ", problems));
        }
    }

    static List<String> problems(Environment env) {
        if (!env.getProperty(SurgeEnv.PUBLIC_DEPLOYMENT, Boolean.class, false)
                || !SENDERS.contains(env.getProperty("spring.application.name", ""))) {
            return List.of();
        }
        Map<String, String> smtp = Binder.get(env)
                .bind("spring.mail.properties", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        List<String> problems = new ArrayList<>();
        if (blank(env.getProperty("spring.mail.host"))) {
            problems.add("set MAIL_HOST");
        }
        if (blank(env.getProperty("spring.mail.username")) || blank(env.getProperty("spring.mail.password"))) {
            problems.add("set MAIL_USERNAME and MAIL_PASSWORD (a secret file)");
        }
        if (!isTrue(smtp.get("mail.smtp.auth"))) {
            problems.add("set MAIL_SMTP_AUTH=true");
        }
        if (!isTrue(smtp.get("mail.smtp.starttls.enable")) || !isTrue(smtp.get("mail.smtp.starttls.required"))) {
            problems.add("set MAIL_STARTTLS=true (STARTTLS enabled and required)");
        }
        String from = env.getProperty("surge.mail.from", "");
        if (blank(from) || from.toLowerCase(Locale.ROOT).replace(">", "").strip().endsWith("@surge.local")) {
            problems.add("set MAIL_FROM to an address on your own domain (not surge.local)");
        }
        return problems;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isTrue(String value) {
        return value != null && "true".equalsIgnoreCase(value.strip());
    }
}
