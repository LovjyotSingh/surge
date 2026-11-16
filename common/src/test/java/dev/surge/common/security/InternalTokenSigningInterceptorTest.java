package dev.surge.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import dev.surge.common.web.RequestIdFilter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/** What a service-to-service call carries: a fresh token for exactly one audience, and the request id. */
class InternalTokenSigningInterceptorTest {

    private static final JWKSet DEV_JWKS = InternalKeys.loadVerificationKeys(null, SurgeEnv.DEV,
            new DefaultResourceLoader());

    private final List<HttpRequest> sent = new ArrayList<>();
    private final ClientHttpRequestExecution execution = (request, body) -> {
        sent.add(request);
        return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
    };

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private HttpRequest call(InternalTokenSigningInterceptor interceptor, MockClientHttpRequest request)
            throws Exception {
        interceptor.intercept(request, new byte[0], execution);
        return sent.getLast();
    }

    private static MockClientHttpRequest request() {
        return new MockClientHttpRequest(HttpMethod.POST, URI.create("http://payment-service/api/payments"));
    }

    private static InternalIdentity verify(String token, String issuer, String audience) {
        return new InternalTokenVerifier(DEV_JWKS, Set.of(issuer), audience).verify(token);
    }

    @Test
    void everyCallGetsAFreshServiceTokenForTheOneAudience() throws Exception {
        var interceptor = new InternalTokenSigningInterceptor(IdentityTokens.signer("svc:booking"),
                InternalAudiences.PAYMENT);
        String first = call(interceptor, request()).getHeaders().getFirst(InternalTokens.HEADER);
        String second = call(interceptor, request()).getHeaders().getFirst(InternalTokens.HEADER);

        assertThat(first).isNotEqualTo(second);
        for (String token : List.of(first, second)) {
            InternalIdentity identity = verify(token, "svc:booking", "payment-service");
            assertThat(identity.issuer()).isEqualTo("svc:booking");
            assertThat(identity.subject()).isEqualTo("svc:booking");
            assertThat(identity.roles()).isEmpty();
            assertThat(identity.isUser()).isFalse();
        }
        // useless anywhere else
        assertThatThrownBy(() -> verify(first, "svc:booking", "auth-service"))
                .isInstanceOf(InvalidInternalTokenException.class);
    }

    @Test
    void aTokenTheRequestAlreadyCarriedIsReplacedNeverForwarded() throws Exception {
        var interceptor = new InternalTokenSigningInterceptor(IdentityTokens.signer("svc:notification"),
                InternalAudiences.BOOKING);
        MockClientHttpRequest request = request();
        String received = IdentityTokens.admin("notification-service", "u-admin");
        request.getHeaders().add(InternalTokens.HEADER, received);

        HttpRequest out = call(interceptor, request);

        assertThat(out.getHeaders().get(InternalTokens.HEADER)).hasSize(1).doesNotContain(received);
        assertThat(verify(out.getHeaders().getFirst(InternalTokens.HEADER), "svc:notification", "booking-service")
                .subject()).isEqualTo("svc:notification");
    }

    @Test
    void theRequestIdTravelsInTheTokenAndTheHeader() throws Exception {
        var interceptor = new InternalTokenSigningInterceptor(IdentityTokens.signer("svc:booking"),
                InternalAudiences.PAYMENT);
        MDC.put(RequestIdFilter.MDC_KEY, "req-42");
        HttpRequest out = call(interceptor, request());
        assertThat(out.getHeaders().getFirst(RequestIdFilter.HEADER)).isEqualTo("req-42");
        assertThat(verify(out.getHeaders().getFirst(InternalTokens.HEADER), "svc:booking", "payment-service")
                .requestId()).isEqualTo("req-42");

        // an id the call already has is kept
        MockClientHttpRequest withId = request();
        withId.getHeaders().set(RequestIdFilter.HEADER, "caller-set");
        assertThat(call(interceptor, withId).getHeaders().getFirst(RequestIdFilter.HEADER)).isEqualTo("caller-set");

        // a malformed id (RequestIdFilter only bounds the length) is dropped, never forwarded
        MDC.put(RequestIdFilter.MDC_KEY, "bad id\r\nX-Evil: 1");
        HttpRequest bad = call(interceptor, request());
        assertThat(bad.getHeaders().getFirst(RequestIdFilter.HEADER)).isNull();
        assertThat(verify(bad.getHeaders().getFirst(InternalTokens.HEADER), "svc:booking", "payment-service")
                .requestId()).isNull();

        // no request (a message listener thread): still signed, no id
        MDC.clear();
        HttpRequest none = call(interceptor, request());
        assertThat(none.getHeaders().getFirst(RequestIdFilter.HEADER)).isNull();
        assertThat(none.getHeaders().getFirst(InternalTokens.HEADER)).isNotBlank();
    }

    @Test
    void onlyServiceKeysAndKnownAudiences() {
        assertThatThrownBy(() -> new InternalTokenSigningInterceptor(IdentityTokens.signer("gateway"),
                InternalAudiences.PAYMENT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalTokenSigningInterceptor(IdentityTokens.signer("svc:booking"),
                "payments-service")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalTokenSigningInterceptor(null, InternalAudiences.PAYMENT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAudienceComesFromTheClientsTargetCheckedAgainstItsBaseUrl() {
        assertThat(InternalAudiences.forClient("catalog-service", "http://catalog-service")).isEqualTo(
                "catalog-service");
        assertThat(InternalAudiences.forClient("catalog-service", "http://catalog-service:8083/x")).isEqualTo(
                "catalog-service");
        // a stub or a direct address keeps the client's own target
        assertThat(InternalAudiences.forClient("catalog-service", "http://localhost:51234/")).isEqualTo(
                "catalog-service");
        assertThat(InternalAudiences.forClient("auth-service", "http://10.0.0.7:8082")).isEqualTo("auth-service");
        // a base URL that names another service fails startup
        for (String wrong : List.of("http://booking-service", "http://BOOKING-SERVICE:8081")) {
            assertThatThrownBy(() -> InternalAudiences.forClient("catalog-service", wrong))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("wrong service");
        }
        assertThatThrownBy(() -> InternalAudiences.forClient("catalogue", "http://catalog-service"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InternalAudiences.forClient("catalog-service", "http://bad host/"))
                .isInstanceOf(IllegalStateException.class);
    }
}
