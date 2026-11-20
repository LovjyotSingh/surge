package dev.surge.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private static final String UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final RequestIdFilter filter = new RequestIdFilter();

    private MockHttpServletResponse run(String header, AtomicReference<String> mdcSeen) throws Exception {
        var request = new MockHttpServletRequest("GET", "/x");
        if (header != null) {
            request.addHeader("X-Request-Id", header);
        }
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                mdcSeen.set(MDC.get("requestId"));
                super.doFilter(req, res);
            }
        };
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void generatesIdWhenMissing() throws Exception {
        var seen = new AtomicReference<String>();
        var response = run(null, seen);
        assertThat(response.getHeader("X-Request-Id")).matches(UUID_REGEX);
        assertThat(seen.get()).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void keepsIncomingId() throws Exception {
        var seen = new AtomicReference<String>();
        var response = run("abc-123", seen);
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("abc-123");
        assertThat(seen.get()).isEqualTo("abc-123");
    }

    @Test
    void replacesOverlongId() throws Exception {
        var seen = new AtomicReference<String>();
        var response = run("a".repeat(65), seen);
        assertThat(response.getHeader("X-Request-Id")).matches(UUID_REGEX);
    }

    /** SEC-26: the gateway's rule, so a caller that reaches a service directly cannot forge log lines. */
    @Test
    void replacesAnythingButTheSafeCharacters() throws Exception {
        for (String unsafe : new String[] {"a b<script>", "abc\nrequestId=evil", "a\tb", "x;y", "ä", "a%20b",
                "id=1", "\"q\""}) {
            var seen = new AtomicReference<String>();
            var response = run(unsafe, seen);
            assertThat(response.getHeader("X-Request-Id")).as(unsafe).matches(UUID_REGEX);
            assertThat(seen.get()).as(unsafe).matches(UUID_REGEX);
        }
    }

    @Test
    void keepsEverySafeId() throws Exception {
        for (String safe : new String[] {"a", "A.b_c-D9", "x".repeat(64),
                "0f8fad5b-d9cb-469f-a165-70867728950e"}) {
            var seen = new AtomicReference<String>();
            assertThat(run(safe, seen).getHeader("X-Request-Id")).isEqualTo(safe);
            assertThat(seen.get()).isEqualTo(safe);
        }
    }
}
