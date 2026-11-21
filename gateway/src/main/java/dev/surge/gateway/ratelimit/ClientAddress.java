package dev.surge.gateway.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;

/**
 * Who the client is, for the per-IP rate limits (SEC-10, SEC-11).
 *
 * <ul>
 *   <li>The socket peer is the client, unless the peer matches {@code surge.security.trusted-proxies}: a regex over
 *       the peer's address string ({@code InetSocketAddress.getHostString()}, so IPv6 in full form such as
 *       {@code 2001:db8:0:0:0:0:0:53}), the same value and the same matching as Spring Cloud Gateway's own
 *       {@code spring.cloud.gateway.server.webflux.trusted-proxies}. Blank (the default) trusts no proxy.</li>
 *   <li>Behind a trusted proxy the client is the LAST {@code X-Forwarded-For} hop: the address that proxy (nginx, which
 *       overwrites the header with {@code $remote_addr}) saw. A client can only forge the earlier hops. A missing or
 *       malformed hop falls back to the peer. Hops are parsed as IP literals only, never looked up in DNS.</li>
 *   <li>IPv6 clients are keyed by their /64: one subscriber usually holds a whole /64, so keying by full address
 *       would give an attacker 2^64 buckets.</li>
 * </ul>
 */
public final class ClientAddress {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private static final Logger log = LoggerFactory.getLogger(ClientAddress.class);

    private final Pattern trustedProxies;
    private final AtomicBoolean warnedUntrustedForwarder = new AtomicBoolean();

    /** @param trustedProxies regex matched against the whole peer address; null or blank trusts no proxy */
    public ClientAddress(String trustedProxies) {
        this.trustedProxies = trustedProxies == null || trustedProxies.isBlank() ? null
                : Pattern.compile(trustedProxies);
    }

    /** The rate-limit key: an IPv4 address, or an IPv6 /64 such as {@code 2001:db8:1:2:0:0:0:0/64}; null if unknown. */
    public String keyOf(ServerWebExchange exchange) {
        InetSocketAddress peer = exchange.getRequest().getRemoteAddress();
        if (peer == null) {
            return null;
        }
        InetAddress client = peer.getAddress() != null ? peer.getAddress() : literal(peer.getHostString());
        if (isTrusted(peer)) {
            InetAddress forwarded = lastForwardedHop(exchange.getRequest().getHeaders());
            if (forwarded != null) {
                client = forwarded;
            }
        } else if (exchange.getRequest().getHeaders().containsHeader(FORWARDED_FOR)
                && warnedUntrustedForwarder.compareAndSet(false, true)) {
            // nginx always sends the header, so this is most likely nginx itself, not matched by the pattern.
            log.warn("Peer {} sent X-Forwarded-For but is not a trusted proxy, so the header is ignored. If {} is "
                    + "nginx, SURGE_TRUSTED_PROXIES does not match it and every client shares nginx's rate-limit "
                    + "bucket. (Logged once.)", peer.getHostString(), peer.getHostString());
        }
        return client == null ? null : key(client);
    }

    private boolean isTrusted(InetSocketAddress peer) {
        return trustedProxies != null && trustedProxies.matcher(peer.getHostString()).matches();
    }

    private static InetAddress lastForwardedHop(HttpHeaders headers) {
        List<String> lines = headers.getOrEmpty(FORWARDED_FOR);
        if (lines.isEmpty()) {
            return null;
        }
        String[] hops = String.join(",", lines).split(",");
        return hops.length == 0 ? null : literal(hops[hops.length - 1].trim());
    }

    /** Parses an IP literal without any name lookup; null when it is not one. */
    private static InetAddress literal(String value) {
        if (value == null || !IP_LITERAL.matcher(value).matches()) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String key(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address.getHostAddress();
        }
        byte[] prefix = address.getAddress();
        Arrays.fill(prefix, 8, 16, (byte) 0);
        try {
            return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
        } catch (UnknownHostException e) {
            throw new IllegalStateException("16 bytes are always an IPv6 address", e);
        }
    }
}
