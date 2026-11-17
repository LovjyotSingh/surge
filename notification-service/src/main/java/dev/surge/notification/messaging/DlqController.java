package dev.surge.notification.messaging;

import dev.surge.common.security.AdminAudit;
import dev.surge.notification.error.InvalidParameterException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin view of the dead-letter queue. The gateway and the identity chain let only ADMINs reach it; the controller
 * checks again ({@code CurrentIdentity}, through {@link AdminAudit}) before it judges the {@code String} parameters
 * (which cannot fail to bind), and writes one {@code ADMIN_ACTION} line per successful action, never message bodies.
 */
@RestController
@RequestMapping("/api/notifications/admin/dlq")
public class DlqController {

    private static final int MAX_LIMIT = 100;
    private static final String TARGET = "queue:tickets.dlq";

    private final DlqOperations dlq;

    public DlqController(DlqOperations dlq) {
        this.dlq = dlq;
    }

    @GetMapping
    public List<DlqOperations.DlqMessage> list(@RequestParam(name = "limit", defaultValue = "20") String limit) {
        AdminAudit.requireAdmin("notification.dlq.list");
        List<DlqOperations.DlqMessage> messages = dlq.list(parseLimit(limit));
        AdminAudit.log("notification.dlq.list", TARGET);
        return messages;
    }

    @PostMapping("/replay")
    public Map<String, Integer> replay(@RequestParam(name = "limit", defaultValue = "20") String limit) {
        AdminAudit.requireAdmin("notification.dlq.replay");
        int replayed = dlq.replay(parseLimit(limit));
        AdminAudit.log("notification.dlq.replay", TARGET + ",replayed:" + replayed);
        return Map.of("replayed", replayed);
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
