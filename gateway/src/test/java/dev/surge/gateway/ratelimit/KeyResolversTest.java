package dev.surge.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

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

    @Test
    void fallsBackToTheRemoteAddress() {
        assertThat(key()).isEqualTo("192.0.2.9");
        assertThat(key("")).isEqualTo("192.0.2.9");
        assertThat(key("   ")).isEqualTo("192.0.2.9");
        assertThat(key("1.1.1.1, not an ip!")).isEqualTo("192.0.2.9");
    }
}
