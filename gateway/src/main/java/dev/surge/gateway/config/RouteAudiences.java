package dev.surge.gateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

/**
 * Route metadata {@value #METADATA_KEY}: the {@code spring.application.name} of the service a route targets, which
 * becomes the {@code aud} of the internal identity token the gateway signs for it. Every route must have one, it must
 * be one of the {@link #KNOWN} services, and for an {@code lb://<service>} target it must be that service: a typo
 * fails startup instead of minting tokens for the wrong (or no) service.
 */
public final class RouteAudiences {

    public static final String METADATA_KEY = "surge.audience";

    public static final String AUTH = "auth-service";
    public static final String CATALOG = "catalog-service";
    public static final String BOOKING = "booking-service";
    public static final String NOTIFICATION = "notification-service";
    public static final String PAYMENT = "payment-service";
    /** Every service the gateway routes to. */
    public static final Set<String> KNOWN = Set.of(AUTH, CATALOG, BOOKING, NOTIFICATION, PAYMENT);

    private RouteAudiences() {
    }

    /** The route's audience, or null when it has none. */
    public static String of(Route route) {
        Object audience = route == null ? null : route.getMetadata().get(METADATA_KEY);
        return audience instanceof String name && !name.isBlank() ? name : null;
    }

    /** Fails startup when any route lacks a valid audience, naming every such route. */
    public static RouteLocator requireAll(RouteLocator locator) {
        List<Route> routes = locator.getRoutes().collectList().block(Duration.ofSeconds(10));
        List<String> problems = new ArrayList<>();
        for (Route route : routes == null ? List.<Route>of() : routes) {
            String audience = of(route);
            URI uri = route.getUri();
            if (audience == null) {
                problems.add(route.getId() + " (no audience)");
            } else if (!KNOWN.contains(audience)) {
                problems.add(route.getId() + " (unknown audience '" + audience + "')");
            } else if ("lb".equals(uri.getScheme()) && !audience.equals(uri.getHost())) {
                problems.add(route.getId() + " (audience '" + audience + "' but target " + uri + ")");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Every gateway route needs metadata '" + METADATA_KEY + "' naming its "
                    + "target service, one of " + KNOWN.stream().sorted().toList() + ": "
                    + String.join(", ", problems));
        }
        return locator;
    }
}
