package dev.surge.notification.audit;

import dev.surge.common.security.AdminAudit;
import dev.surge.notification.error.InvalidParameterException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Admin view of the daily audit file. The gateway and the identity chain let only ADMINs reach it; the controller
 * checks again ({@code CurrentIdentity}, through {@link AdminAudit}) before it judges the {@code String} parameters
 * (which cannot fail to bind), and writes one {@code ADMIN_ACTION} line per successful read.
 */
@RestController
@RequestMapping("/api/notifications/admin/audit")
public class AuditController {

    private static final int MAX_TAIL = 500;

    private final AuditLogWriter writer;
    private final JsonMapper json;

    public AuditController(AuditLogWriter writer, JsonMapper json) {
        this.writer = writer;
        this.json = json;
    }

    public record AuditView(String date, long lines, List<JsonNode> tail) {
    }

    @GetMapping
    public AuditView audit(@RequestParam(name = "date", required = false) String date,
                           @RequestParam(name = "tail", defaultValue = "50") String tail) {
        AdminAudit.requireAdmin("notification.audit.tail");
        LocalDate day = date == null ? writer.today() : parseDate(date);
        AuditLogWriter.Tail result = writer.tail(day, parseTail(tail));
        AuditView view = new AuditView(day.toString(), result.lines(),
                result.tail().stream().map(this::parseLine).toList());
        AdminAudit.log("notification.audit.tail", "audit:" + day);
        return view;
    }

    private JsonNode parseLine(String line) {
        try {
            return writer.parse(line);
        } catch (RuntimeException e) {
            return json.getNodeFactory().stringNode(line);
        }
    }

    private static LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidParameterException("date must look like 2026-11-14");
        }
    }

    private static int parseTail(String raw) {
        try {
            int tail = Integer.parseInt(raw.trim());
            if (tail >= 1 && tail <= MAX_TAIL) {
                return tail;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the same message
        }
        throw new InvalidParameterException("tail must be a whole number from 1 to " + MAX_TAIL);
    }
}
