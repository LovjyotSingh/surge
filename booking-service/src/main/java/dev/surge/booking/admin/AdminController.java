package dev.surge.booking.admin;

import dev.surge.booking.error.EventNotFoundException;
import dev.surge.booking.error.InvalidParameterException;
import dev.surge.booking.error.MalformedReportException;
import dev.surge.booking.error.ReportNotFoundException;
import dev.surge.booking.error.ReportTooLargeException;
import dev.surge.booking.event.EventRefRepository;
import dev.surge.booking.hold.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Operator endpoints used by the load tester and the web Ops page. */
@RestController
@RequestMapping("/api/bookings/admin")
public class AdminController {

    static final int MAX_OPENS_IN_SECONDS = 86_400;
    static final int MAX_REPORT_BYTES = 256 * 1024;

    private final JdbcTemplate jdbc;
    private final EventRefRepository events;
    private final EventResetService resetService;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public AdminController(JdbcTemplate jdbc, EventRefRepository events, EventResetService resetService,
                           StringRedisTemplate redis, JsonMapper json) {
        this.jdbc = jdbc;
        this.events = events;
        this.resetService = resetService;
        this.redis = redis;
        this.json = json;
    }

    @GetMapping("/events/{eventId}/integrity")
    public IntegrityReport integrity(@PathVariable long eventId) {
        if (!events.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        int total = count("SELECT COUNT(*) FROM seat WHERE event_id = ?", eventId);
        int sold = count("SELECT COUNT(*) FROM seat WHERE event_id = ? AND status = 'SOLD'", eventId);
        int items = count("SELECT COUNT(*) FROM order_item oi JOIN orders o ON o.id = oi.order_id "
                + "WHERE o.event_id = ?", eventId);
        int distinct = count("SELECT COUNT(DISTINCT oi.seat_id) FROM order_item oi JOIN orders o ON o.id = oi.order_id "
                + "WHERE o.event_id = ?", eventId);
        return IntegrityReport.of(eventId, total, sold, items, distinct);
    }

    @PostMapping("/events/{eventId}/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable long eventId, @RequestParam(defaultValue = "0") int opensInSeconds) {
        if (opensInSeconds < 0 || opensInSeconds > MAX_OPENS_IN_SECONDS) {
            throw new InvalidParameterException("opensInSeconds must be between 0 and " + MAX_OPENS_IN_SECONDS);
        }
        resetService.reset(eventId, opensInSeconds);
    }

    @PutMapping("/loadtest-reports/latest")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putReport(@RequestBody byte[] body) {
        if (body.length > MAX_REPORT_BYTES) {
            throw new ReportTooLargeException(MAX_REPORT_BYTES);
        }
        JsonNode parsed;
        try {
            parsed = json.readTree(body);
        } catch (JacksonException e) {
            throw new MalformedReportException();
        }
        if (parsed == null || parsed.isMissingNode()) {
            throw new MalformedReportException();
        }
        redis.opsForValue().set(RedisKeys.LOADTEST_LATEST, json.writeValueAsString(parsed));
    }

    @GetMapping("/loadtest-reports/latest")
    public ResponseEntity<String> getReport() {
        String stored = redis.opsForValue().get(RedisKeys.LOADTEST_LATEST);
        if (stored == null) {
            throw new ReportNotFoundException();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(stored);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
