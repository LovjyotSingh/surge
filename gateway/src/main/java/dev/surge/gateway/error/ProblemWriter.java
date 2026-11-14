package dev.surge.gateway.error;

import dev.surge.gateway.RequestIdFilter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Writes RFC 9457 problems with the same shape the MVC services produce ({@code type}, {@code requestId}). */
@Component
public class ProblemWriter {

    public static final String TYPE_BASE = "https://surge.dev/problems/";

    private final JsonMapper mapper;

    public ProblemWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, String slug, String title,
            String detail) {
        return write(exchange, status, slug, title, detail, null);
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, String slug, String title,
            String detail, Map<String, String> extraHeaders) {
        var response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", TYPE_BASE + slug);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", exchange.getRequest().getPath().value());
        body.put("requestId", RequestIdFilter.of(exchange));
        byte[] bytes = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);

        response.setStatusCode(status);
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.setContentLength(bytes.length);
        if (extraHeaders != null) {
            extraHeaders.forEach(headers::set);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
