package dev.surge.notification.messaging;

import dev.surge.notification.error.ForbiddenException;
import dev.surge.notification.error.InvalidParameterException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin view of the dead-letter queue. The gateway lets only ADMIN tokens reach it and sets {@code X-User-Roles}. */
@RestController
@RequestMapping("/api/notifications/admin/dlq")
public class DlqController {

    private static final int MAX_LIMIT = 100;

    private final DlqOperations dlq;

    public DlqController(DlqOperations dlq) {
        this.dlq = dlq;
    }

    @GetMapping
    public List<DlqOperations.DlqMessage> list(@RequestHeader(name = "X-User-Roles", required = false) String roles,
                                               @RequestParam(name = "limit", defaultValue = "20") String limit) {
        requireAdmin(roles);
        return dlq.list(parseLimit(limit));
    }

    @PostMapping("/replay")
    public Map<String, Integer> replay(@RequestHeader(name = "X-User-Roles", required = false) String roles,
                                       @RequestParam(name = "limit", defaultValue = "20") String limit) {
        requireAdmin(roles);
        return Map.of("replayed", dlq.replay(parseLimit(limit)));
    }

    /** The exact token ADMIN in a comma-separated list; "SUPERADMIN" or "admin" do not count. */
    static void requireAdmin(String roles) {
        if (roles != null) {
            for (String role : roles.split(",")) {
                if (role.trim().equals("ADMIN")) {
                    return;
                }
            }
        }
        throw new ForbiddenException();
    }

    static int parseLimit(String raw) {
        try {
            int limit = Integer.parseInt(raw.trim());
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the same message
        }
        throw new InvalidParameterException("limit must be a whole number from 1 to " + MAX_LIMIT);
    }
}
