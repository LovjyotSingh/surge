package dev.surge.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

/**
 * Route metadata {@value #METADATA_KEY}: the {@code spring.application.name} of the service a route targets, which
 * becomes the {@code aud} of the internal identity token the gateway signs for it. Every route must have one.
 */
public final class RouteAudiences {

    public static final String METADATA_KEY = "surge.audience";

    public static final String AUTH = "auth-service";
    public static final String CATALOG = "catalog-service";
    public static final String BOOKING = "booking-service";
    public static final String NOTIFICATION = "notification-service";
    public static final String PAYMENT = "payment-service";

    private RouteAudiences() {
    }

    /** The route's audience, or null when it has none. */
    public static String of(Route route) {
        Object audience = route == null ? null : route.getMetadata().get(METADATA_KEY);
        return audience instanceof String name && !name.isBlank() ? name : null;
    }

    /** Fails startup when any route lacks an audience, naming every such route. */
    public static RouteLocator requireAll(RouteLocator locator) {
        List<Route> routes = locator.getRoutes().collectList().block(Duration.ofSeconds(10));
        List<String> missing = routes == null ? List.of()
                : routes.stream().filter(route -> of(route) == null).map(Route::getId).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Every gateway route needs metadata '" + METADATA_KEY
                    + "' (the target's spring.application.name); missing on: " + String.join(", ", missing));
        }
        return locator;
    }
}
