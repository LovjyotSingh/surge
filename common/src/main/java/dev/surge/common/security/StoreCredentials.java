package dev.surge.common.security;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/**
 * SEC-04, SEC-05, SEC-06 and SEC-15, client side: with {@code SURGE_ENV=prod} a service refuses to start while a
 * store or platform server it uses has no credentials, or still has the development ones. Which stores a service
 * uses is listed here once, by {@code spring.application.name}, so no property can drop a requirement. The checks
 * read the resolved Spring properties (what the clients really use), and every service's application.yml fills
 * them from the environment variables named in the messages. Messages name variables, never values.
 *
 * <p>In dev (and tests) nothing is required: the brokers and stores are unauthenticated locally, and the Eureka and
 * config-server clients send the development credentials that those servers accept only in dev.
 */
public final class StoreCredentials {

    /** The pair every service's application.yml sends to Eureka when EUREKA_USER / EUREKA_PASSWORD are unset. */
    static final String DEV_EUREKA_PASSWORD = "dev-only-eureka-password";
    /** The pair every service's application.yml sends to the config server when CONFIG_USER / _PASSWORD are unset. */
    static final String DEV_CONFIG_PASSWORD = "dev-only-config-password";
    /** application.yml's default MYSQL_PASSWORD (and .env.example's). */
    static final String DEV_MYSQL_PASSWORD = "surge";

    enum Store { MYSQL, KAFKA, RABBIT, REDIS, ELASTICSEARCH, EUREKA, CONFIG }

    /** The stores each service uses; a service not listed needs nothing. Keep in step with each application.yml. */
    static final Map<String, Set<Store>> STORES_BY_SERVICE = Map.of(
            "gateway", EnumSet.of(Store.REDIS, Store.EUREKA, Store.CONFIG),
            "auth-service", EnumSet.of(Store.MYSQL, Store.EUREKA, Store.CONFIG),
            "booking-service", EnumSet.of(Store.MYSQL, Store.KAFKA, Store.RABBIT, Store.REDIS, Store.EUREKA,
                    Store.CONFIG),
            "catalog-service", EnumSet.of(Store.MYSQL, Store.KAFKA, Store.ELASTICSEARCH, Store.EUREKA, Store.CONFIG),
            "payment-service", EnumSet.of(Store.EUREKA, Store.CONFIG),
            "notification-service", EnumSet.of(Store.MYSQL, Store.KAFKA, Store.RABBIT, Store.EUREKA, Store.CONFIG));

    private static final Set<String> SASL_PROTOCOLS = Set.of("SASL_PLAINTEXT", "SASL_SSL");
    /**
     * Eureka and config-server user names and passwords: they travel in a URL's userinfo (Eureka) or must match what
     * discovery and config-server accept, so only unreserved URL characters, never percent-encoding.
     */
    private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9._~-]+");

    private StoreCredentials() {
    }

    /**
     * Refuses to start in prod while {@link #problems} lists anything, or when this application is not in
     * {@link #STORES_BY_SERVICE} at all (nothing would be checked). Dev is never checked.
     */
    public static void check(Environment environment) {
        if (SurgeEnv.from(environment) != SurgeEnv.PROD) {
            return;
        }
        String application = environment.getProperty("spring.application.name", "");
        if (!STORES_BY_SERVICE.containsKey(application)) {
            throw new IllegalStateException("SURGE_ENV=prod: spring.application.name '" + application
                    + "' is not in StoreCredentials.STORES_BY_SERVICE; list the stores it uses");
        }
        List<String> problems = problems(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("SURGE_ENV=prod needs credentials for every store this service uses: "
                    + String.join("; ", problems));
        }
    }

    /** What is missing for this service, one line per store, naming the environment variables to set. */
    static List<String> problems(Environment env) {
        Set<Store> stores = STORES_BY_SERVICE.getOrDefault(env.getProperty("spring.application.name", ""),
                EnumSet.noneOf(Store.class));
        List<String> problems = new ArrayList<>();
        for (Store store : stores) {
            if (!satisfied(store, env)) {
                problems.add(message(store));
            }
        }
        return problems;
    }

    private static boolean satisfied(Store store, Environment env) {
        return switch (store) {
            case MYSQL -> has(env, "spring.datasource.username")
                    && has(env, "spring.datasource.password", p -> !p.equals(DEV_MYSQL_PASSWORD));
            // the JAAS line quotes both: a quote or backslash would break or rewrite that line
            case KAFKA -> SASL_PROTOCOLS.contains(env.getProperty("spring.kafka.properties.security.protocol", ""))
                    && has(env, "surge.kafka.username", StoreCredentials::jaasSafe)
                    && has(env, "surge.kafka.password", StoreCredentials::jaasSafe);
            case RABBIT -> has(env, "spring.rabbitmq.username", u -> !u.equals("guest"))
                    && has(env, "spring.rabbitmq.password", p -> !p.equals("guest"));
            case REDIS -> has(env, "spring.data.redis.password");
            case ELASTICSEARCH -> has(env, "spring.elasticsearch.username")
                    && has(env, "spring.elasticsearch.password");
            case EUREKA -> disabled(env, "eureka.client.enabled") || disabled(env, "spring.cloud.discovery.enabled")
                    || eurekaZonesCarryCredentials(env.getProperty("eureka.client.service-url.defaultZone", ""));
            case CONFIG -> disabled(env, "spring.cloud.config.enabled")
                    || has(env, "spring.cloud.config.username", StoreCredentials::urlSafe)
                    && has(env, "spring.cloud.config.password",
                            p -> urlSafe(p) && !p.equals(DEV_CONFIG_PASSWORD));
        };
    }

    private static String message(Store store) {
        return switch (store) {
            case MYSQL -> "MySQL: set MYSQL_USER and MYSQL_PASSWORD (not the development default)";
            case KAFKA -> "Kafka: set KAFKA_SECURITY_PROTOCOL (SASL_PLAINTEXT or SASL_SSL), KAFKA_USER and "
                    + "KAFKA_PASSWORD (neither with quotes or backslashes)";
            case RABBIT -> "RabbitMQ: set RABBIT_USER and RABBIT_PASSWORD (not guest)";
            case REDIS -> "Redis: set REDIS_PASSWORD (and REDIS_USER for an ACL user)";
            case ELASTICSEARCH -> "Elasticsearch: set ELASTICSEARCH_USER and ELASTICSEARCH_PASSWORD";
            case EUREKA -> "Eureka: set EUREKA_USER and EUREKA_PASSWORD (letters, digits and . _ ~ - only; not the "
                    + "development ones)";
            case CONFIG -> "Config server: set CONFIG_USER and CONFIG_PASSWORD (letters, digits and . _ ~ - only; "
                    + "not the development ones)";
        };
    }

    /** Every zone URL is {@code scheme://user:password@host...} with both parts set and not the development pair. */
    private static boolean eurekaZonesCarryCredentials(String zones) {
        if (zones.isBlank()) {
            return false;
        }
        for (String zone : zones.split(",")) {
            String userInfo;
            try {
                userInfo = URI.create(zone.strip()).getRawUserInfo();
            } catch (IllegalArgumentException e) {
                return false;
            }
            int colon = userInfo == null ? -1 : userInfo.indexOf(':');
            if (colon < 0) {
                return false;
            }
            String user = userInfo.substring(0, colon);
            String password = userInfo.substring(colon + 1);
            if (!urlSafe(user) || !urlSafe(password) || password.equals(DEV_EUREKA_PASSWORD)) {
                return false;
            }
        }
        return true;
    }

    private static boolean urlSafe(String value) {
        return URL_SAFE.matcher(value).matches();
    }

    private static boolean jaasSafe(String value) {
        return value.indexOf('"') < 0 && value.indexOf('\\') < 0;
    }

    private static boolean has(Environment env, String property) {
        return has(env, property, value -> true);
    }

    private static boolean has(Environment env, String property, Predicate<String> acceptable) {
        String value = env.getProperty(property, "");
        return !value.isBlank() && acceptable.test(value);
    }

    private static boolean disabled(Environment env, String property) {
        return "false".equalsIgnoreCase(env.getProperty(property, "").strip());
    }
}
