package dev.surge.common.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * The services an internal identity token can be addressed to: its {@code aud} is the target's
 * {@code spring.application.name}, which is also its Eureka/load-balancer name. The gateway's route metadata and the
 * service-to-service clients both use these names, so neither can address a token to a service that does not exist.
 */
public final class InternalAudiences {

    public static final String AUTH = "auth-service";
    public static final String BOOKING = "booking-service";
    public static final String CATALOG = "catalog-service";
    public static final String NOTIFICATION = "notification-service";
    public static final String PAYMENT = "payment-service";
    /** Every service a token may be addressed to. */
    public static final Set<String> KNOWN = Set.of(AUTH, BOOKING, CATALOG, NOTIFICATION, PAYMENT);

    private InternalAudiences() {
    }

    /**
     * The audience for a client of {@code service} whose configured base URL is {@code baseUrl}, decided once at
     * startup and never from a request. When the base URL's host names a service ({@code http://catalog-service},
     * resolved by the load balancer), that name IS the audience, and it must be the service the client is for: a
     * client wired to the wrong service fails startup instead of sending tokens the target refuses (or another service
     * accepts). Any other host (a direct address, a test stub) keeps the client's own target service.
     */
    public static String forClient(String service, String baseUrl) {
        if (!KNOWN.contains(service)) {
            throw new IllegalArgumentException("Unknown service '" + service + "', expected one of "
                    + KNOWN.stream().sorted().toList());
        }
        String host;
        try {
            host = URI.create(baseUrl).getHost();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("The base URL of the " + service + " client is not a valid URL");
        }
        if (host != null && KNOWN.contains(host.toLowerCase(Locale.ROOT)) && !service.equals(host.toLowerCase(
                Locale.ROOT))) {
            throw new IllegalStateException("The " + service + " client's base URL names " + host
                    + ": its identity tokens would be addressed to the wrong service");
        }
        return service;
    }
}
