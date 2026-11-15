package dev.surge.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class KeyResolversTest {

    private static String key(String... forwardedLines) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new InetSocketAddress("192.0.2.9", 5555));
        if (forwardedLines.length > 0) {
            builder.header("X-Forwarded-For", forwardedLines);
        }
        return KeyResolvers.clientIp(MockServerWebExchange.from(builder));
    }

    @Test
    void usesTheLastHopTrimmed() {
        assertThat(key("1.1.1.1, 9.9.9.9")).isEqualTo("9.9.9.9");
        assertThat(key("  9.9.9.9  ")).isEqualTo("9.9.9.9");
        assertThat(key("1.1.1.1", "2.2.2.2, 9.9.9.9")).isEqualTo("9.9.9.9");
        assertThat(key("2001:db8::1")).isEqualTo("2001:db8::1");
    }

    /** The holds/orders buckets key on the verified token's subject; a forged X-User-Id never picks the bucket. */
    @Test
    void theUserKeyIsTheVerifiedPrincipalNeverAHeader() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/bookings/holds")
                .header("X-User-Id", "u-forged"));
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("u-real").build();
        String key = KeyResolvers.user().resolve(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt)))
                .block();
        assertThat(key).isEqualTo("u-real");
        // no verified user: no key at all (RateLimitFilter then refuses), whatever the headers say
        assertThat(KeyResolvers.user().resolve(exchange).block()).isNull();
    }

    @Test
    void fallsBackToTheRemoteAddress() {
        assertThat(key()).isEqualTo("192.0.2.9");
        assertThat(key("")).isEqualTo("192.0.2.9");
        assertThat(key("   ")).isEqualTo("192.0.2.9");
        assertThat(key("1.1.1.1, not an ip!")).isEqualTo("192.0.2.9");
    }
}
