package dev.surge.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;

class KeyResolversTest {

    /** nginx's subnet in these tests; the gateway trusts X-Forwarded-For only from a peer that matches it. */
    private static final ClientAddress BEHIND_NGINX = new ClientAddress("192[.]0[.]2[.][0-9]+");
    /** The default: no proxy is trusted, so the socket peer is always the client. */
    private static final ClientAddress NO_PROXY = new ClientAddress("");

    private static ServerWebExchange exchange(String peer, String... forwardedLines) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new InetSocketAddress(peer, 5555));
        if (forwardedLines.length > 0) {
            builder.header("X-Forwarded-For", forwardedLines);
        }
        return MockServerWebExchange.from(builder);
    }

    /** A request that arrives through nginx (peer 192.0.2.9, trusted). */
    private static String key(String... forwardedLines) {
        return BEHIND_NGINX.keyOf(exchange("192.0.2.9", forwardedLines));
    }

    @Test
    void behindATrustedProxyTheLastHopIsTheClient() {
        assertThat(key("1.1.1.1, 9.9.9.9")).isEqualTo("9.9.9.9");
        assertThat(key("  9.9.9.9  ")).isEqualTo("9.9.9.9");
        assertThat(key("1.1.1.1", "2.2.2.2, 9.9.9.9")).isEqualTo("9.9.9.9");
    }

    @Test
    void fallsBackToTheRemoteAddress() {
        assertThat(key()).isEqualTo("192.0.2.9");
        assertThat(key("")).isEqualTo("192.0.2.9");
        assertThat(key("   ")).isEqualTo("192.0.2.9");
        assertThat(key("1.1.1.1, not an ip!")).isEqualTo("192.0.2.9");
        assertThat(key("1.1.1.1, example.com")).as("never a DNS lookup").isEqualTo("192.0.2.9");
        assertThat(key("1.1.1.1, 999.1.1.1")).isEqualTo("192.0.2.9");
    }

    /** SEC-11: anyone who reaches the gateway directly could otherwise pick any bucket with X-Forwarded-For. */
    @Test
    void aSpoofedForwardedForFromAnUntrustedPeerIsIgnored() {
        assertThat(BEHIND_NGINX.keyOf(exchange("203.0.113.50", "9.9.9.9"))).isEqualTo("203.0.113.50");
        assertThat(BEHIND_NGINX.keyOf(exchange("203.0.113.50", "1.1.1.1, 9.9.9.9"))).isEqualTo("203.0.113.50");
        // the default trusts nobody, not even a peer in nginx's subnet
        assertThat(NO_PROXY.keyOf(exchange("192.0.2.9", "9.9.9.9"))).isEqualTo("192.0.2.9");
        assertThat(new ClientAddress(null).keyOf(exchange("192.0.2.9", "9.9.9.9"))).isEqualTo("192.0.2.9");
        // the pattern must match the whole peer address, not a part of it
        assertThat(BEHIND_NGINX.keyOf(exchange("10.192.0.2", "9.9.9.9"))).isEqualTo("10.192.0.2");
    }

    @Test
    void ipv6ClientsAreKeyedByTheirSlash64() {
        String key = key("2001:db8:1:2::1");
        assertThat(key).isEqualTo("2001:db8:1:2:0:0:0:0/64");
        // any address in the same /64 is the same client
        assertThat(key("2001:db8:1:2:ffff:abcd:1234:9")).isEqualTo(key);
        assertThat(key("2001:0db8:0001:0002:0000:0000:0000:0042")).isEqualTo(key);
        // the next /64 is someone else
        assertThat(key("2001:db8:1:3::1")).isEqualTo("2001:db8:1:3:0:0:0:0/64");
        // a direct IPv6 peer is keyed the same way
        assertThat(NO_PROXY.keyOf(exchange("2001:db8:7:7::5"))).isEqualTo("2001:db8:7:7:0:0:0:0/64");
        // an IPv4-mapped address is the IPv4 client
        assertThat(key("::ffff:9.9.9.9")).isEqualTo("9.9.9.9");
    }

    @Test
    void ipv6ProxiesAreMatchedOnTheirAddress() {
        ClientAddress v6Proxy = new ClientAddress("2001:db8:0:0:0:0:0:53");
        assertThat(v6Proxy.keyOf(exchange("2001:db8::53", "9.9.9.9"))).isEqualTo("9.9.9.9");
        assertThat(v6Proxy.keyOf(exchange("2001:db8::54", "9.9.9.9"))).isEqualTo("2001:db8:0:0:0:0:0:0/64");
    }

    @Test
    void theClientIpResolverUsesTheClientAddress() {
        assertThat(KeyResolvers.clientIp(BEHIND_NGINX).resolve(exchange("192.0.2.9", "9.9.9.9")).block())
                .isEqualTo("9.9.9.9");
        assertThat(KeyResolvers.clientIp(BEHIND_NGINX).resolve(exchange("203.0.113.50", "9.9.9.9")).block())
                .isEqualTo("203.0.113.50");
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

    /**
     * The per-IP hold bucket: every load-test buyer comes from one machine, so a load-test identity (accepted at all
     * only with surge.security.allow-loadtest-subjects=true) is keyed by its own subject instead. Everyone else, a
     * signed-in user included, is keyed by address.
     */
    @Test
    void thePerIpHoldKeyGivesLoadTestBuyersTheirOwnKey() {
        KeyResolver resolver = KeyResolvers.clientIpExceptLoadtest(BEHIND_NGINX);
        Jwt user = Jwt.withTokenValue("t").header("alg", "RS256").subject("u-1").build();
        Jwt ltSubject = Jwt.withTokenValue("t").header("alg", "RS256").subject("lt-abc-1").build();
        Jwt ltClaim = Jwt.withTokenValue("t").header("alg", "RS256").subject("u-2").claim("lt", true).build();

        assertThat(resolveAs(resolver, user)).isEqualTo("ip:9.9.9.9");
        assertThat(resolveAs(resolver, ltSubject)).isEqualTo("lt:lt-abc-1");
        assertThat(resolveAs(resolver, ltClaim)).isEqualTo("lt:u-2");
        assertThat(resolver.resolve(exchange("192.0.2.9", "9.9.9.9")).block()).isEqualTo("ip:9.9.9.9");
    }

    private static String resolveAs(KeyResolver resolver, Jwt jwt) {
        return resolver.resolve(exchange("192.0.2.9", "9.9.9.9"))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt)))
                .block();
    }
}
