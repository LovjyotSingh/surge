package dev.surge.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import dev.surge.common.security.InternalIdentity;
import dev.surge.common.security.InternalKeys;
import dev.surge.common.security.InternalTokenSigner;
import dev.surge.common.security.InternalTokenVerifier;
import dev.surge.common.security.InvalidInternalTokenException;
import dev.surge.common.security.SurgeEnv;
import dev.surge.gateway.config.RouteAudiences;
import dev.surge.gateway.security.InternalIdentityFilter;
import dev.surge.gateway.support.Downstream;
import dev.surge.gateway.support.GatewayTestBase;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * S2a: every routed request carries an {@code X-Surge-Identity} the gateway signed for exactly the target service,
 * whatever the client sent. The tests verify it the way a service will (the committed DEV keys, in dev/tests).
 */
class InternalIdentityTest extends GatewayTestBase {

    private static final String HEADER = "X-Surge-Identity";
    private static final JWKSet DEV_JWKS = InternalKeys.loadVerificationKeys(null, SurgeEnv.DEV,
            new DefaultResourceLoader());

    @Autowired
    private RouteLocator routeLocator;

    /** Exactly one identity header, valid for {@code audience}, signed by the gateway. */
    private static InternalIdentity identityFor(RecordedRequest seen, String audience) {
        assertThat(seen).as("the downstream service received the request").isNotNull();
        List<String> values = seen.getHeaders().values(HEADER);
        assertThat(values).hasSize(1);
        return new InternalTokenVerifier(DEV_JWKS, Set.of("gateway"), audience, Clock.systemUTC())
                .verify(values.get(0));
    }

    private static InternalTokenSigner devSigner(String issuer) {
        return new InternalTokenSigner(InternalKeys.loadSigningKey(null, issuer, SurgeEnv.DEV,
                new DefaultResourceLoader()), issuer, Clock.systemUTC());
    }

    @Test
    void aUserRequestCarriesTheUsersIdentityForTheTargetService() throws Exception {
        client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-s2a-1")))
                .header("X-Request-Id", "rid-s2a-1").bodyValue("{}").exchange().expectStatus().isOk();

        RecordedRequest seen = BOOKING.next();
        InternalIdentity identity = identityFor(seen, "booking-service");
        assertThat(identity.issuer()).isEqualTo("gateway");
        assertThat(identity.subject()).isEqualTo("u-s2a-1");
        assertThat(identity.roles()).containsExactly("USER");
        assertThat(identity.requestId()).isEqualTo("rid-s2a-1");
        // S2a keeps the legacy headers until S2b has moved every service over
        assertThat(seen.getHeaders().get("X-User-Id")).isEqualTo("u-s2a-1");
        assertThat(seen.getHeaders().get("X-User-Roles")).isEqualTo("USER");

        // the same token is useless anywhere else
        String token = seen.getHeaders().get(HEADER);
        assertThatThrownBy(() -> new InternalTokenVerifier(DEV_JWKS, Set.of("gateway"), "payment-service",
                Clock.systemUTC()).verify(token)).isInstanceOf(InvalidInternalTokenException.class);
    }

    @Test
    void adminRolesAreCarried() throws Exception {
        client.post().uri("/api/bookings/admin/reset").header(HttpHeaders.AUTHORIZATION,
                bearer(adminToken("u-s2a-admin"))).exchange().expectStatus().isOk();

        InternalIdentity identity = identityFor(BOOKING.next(), "booking-service");
        assertThat(identity.subject()).isEqualTo("u-s2a-admin");
        assertThat(identity.roles()).containsExactly("USER", "ADMIN");
        assertThat(identity.isGatewayAdmin()).isTrue();
    }

    @Test
    void publicRoutesCarryAnAnonymousIdentity() throws Exception {
        client.get().uri("/api/catalog/events").exchange().expectStatus().isOk();
        InternalIdentity catalog = identityFor(CATALOG.next(), "catalog-service");
        assertThat(catalog.isAnonymous()).isTrue();
        assertThat(catalog.roles()).isEmpty();

        client.get().uri("/api/bookings/events/7").exchange().expectStatus().isOk();
        assertThat(identityFor(BOOKING.next(), "booking-service").isAnonymous()).isTrue();

        client.get().uri("/api/auth/.well-known/jwks.json").exchange().expectStatus().isOk();
        assertThat(identityFor(AUTH.next(), "auth-service").isAnonymous()).isTrue();
    }

    @Test
    void everyServiceGetsATokenAddressedToItself() throws Exception {
        String admin = bearer(adminToken("u-s2a-2"));
        record Case(String method, String path, Downstream target, String audience) {
        }
        for (Case c : List.of(
                new Case("GET", "/api/auth/me", AUTH, "auth-service"),
                new Case("POST", "/api/auth/admin/test-tokens", AUTH, "auth-service"),
                new Case("GET", "/api/catalog/admin/stats/daily", CATALOG, "catalog-service"),
                new Case("PUT", "/api/payments/admin/chaos", PAYMENT, "payment-service"),
                new Case("GET", "/api/notifications/tickets/5", NOTIFICATION, "notification-service"),
                new Case("GET", "/api/notifications/admin/audit", NOTIFICATION, "notification-service"),
                new Case("GET", "/api/bookings/orders/1", BOOKING, "booking-service"))) {
            client.method(org.springframework.http.HttpMethod.valueOf(c.method())).uri(c.path())
                    .header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk();
            InternalIdentity identity = identityFor(c.target().next(), c.audience());
            assertThat(identity.subject()).as(c.path()).isEqualTo("u-s2a-2");
            assertThat(identity.audience()).isEqualTo(c.audience());
        }
    }

    @Test
    void aClientSuppliedIdentityIsDroppedAndReplaced() throws Exception {
        // signed with the real (dev) gateway key, so only stripping can stop it
        String forged = devSigner("gateway").sign("catalog-service", "u-evil", List.of("USER", "ADMIN"), null);
        String forgedService = devSigner("svc:notification").sign("catalog-service", "svc:notification", List.of(),
                null);

        client.get().uri("/api/catalog/events").header(HEADER, forged).header(HEADER, forgedService)
                .exchange().expectStatus().isOk();
        RecordedRequest seen = CATALOG.next();
        InternalIdentity identity = identityFor(seen, "catalog-service");
        assertThat(identity.isAnonymous()).isTrue();
        assertThat(seen.getHeaders().get(HEADER)).isNotEqualTo(forged).isNotEqualTo(forgedService);

        client.post().uri("/api/bookings/holds").header(HttpHeaders.AUTHORIZATION, bearer(userToken("u-s2a-3")))
                .header(HEADER, devSigner("gateway").sign("booking-service", "u-evil", List.of("ADMIN"), null))
                .bodyValue("{}").exchange().expectStatus().isOk();
        InternalIdentity user = identityFor(BOOKING.next(), "booking-service");
        assertThat(user.subject()).isEqualTo("u-s2a-3");
        assertThat(user.roles()).containsExactly("USER");
    }

    @Test
    void everyRouteHasTheAudienceOfTheServiceItTargets() {
        Map<String, String> audienceByUri = Map.of(
                AUTH.url(), "auth-service",
                CATALOG.url(), "catalog-service",
                BOOKING.url(), "booking-service",
                NOTIFICATION.url(), "notification-service",
                PAYMENT.url(), "payment-service");
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotEmpty();
        for (Route route : routes) {
            String uri = route.getUri().getScheme() + "://" + route.getUri().getAuthority();
            assertThat(route.getMetadata().get(RouteAudiences.METADATA_KEY)).as(route.getId())
                    .isEqualTo(audienceByUri.get(uri));
        }
    }

    @Test
    void aRouteWithoutAnAudienceFailsFast() {
        Route naked = Route.async().id("naked").uri("http://localhost:1").predicate(exchange -> true).build();
        RouteLocator locator = () -> Flux.just(naked);
        assertThatThrownBy(() -> RouteAudiences.requireAll(locator)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("naked").hasMessageContaining(RouteAudiences.METADATA_KEY);

        // and if one ever slips through at runtime, the request fails instead of going out unsigned
        InternalIdentityFilter filter = new InternalIdentityFilter(devSigner("gateway"));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, naked);
        assertThatThrownBy(() -> filter.filter(exchange, e -> Mono.empty()).block())
                .isInstanceOf(IllegalStateException.class);
    }
}
