package dev.surge.common.security;

import dev.surge.common.web.RequestIdFilter;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The admin audit trail (SEC-19): every admin handler writes exactly one INFO line when it succeeds,
 *
 * <pre>ADMIN_ACTION actor=&lt;verified user id&gt; action=&lt;name&gt; target=&lt;id&gt; requestId=&lt;id&gt;</pre>
 *
 * and the in-service admin check ({@link #requireAdmin}) writes one {@code ADMIN_ACTION_REFUSED ... status=401|403}
 * line when it refuses. The actor is the subject of the verified identity token ({@link CurrentIdentity}), never a
 * header; the request id comes from the MDC ({@link RequestIdFilter}). Callers pass an action name and a target id
 * they built themselves: never a secret, a token or a request payload. Every value is also reduced to
 * {@code [A-Za-z0-9._:,/@-]} and {@value #MAX_VALUE} characters, so no value can add a field or a line.
 *
 * <p>The lines go to the logger {@value #LOGGER}, so a deployment can route them to their own appender.
 */
public final class AdminAudit {

    public static final String LOGGER = "surge.admin-audit";
    static final int MAX_VALUE = 128;
    private static final String NONE = "-";
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._:,/@-]");
    private static final Logger log = LoggerFactory.getLogger(LOGGER);

    private AdminAudit() {
    }

    /** The success line, after the action has been done. {@code target} null is written as {@code -}. */
    public static void log(String action, Object target) {
        log.info("ADMIN_ACTION actor={} action={} target={} requestId={}", actor(), clean(action), clean(target),
                requestId());
    }

    /**
     * {@link CurrentIdentity#requireAdmin()}, writing one {@code ADMIN_ACTION_REFUSED} line before it rethrows the
     * same 401/403 problem.
     */
    public static InternalIdentity requireAdmin(String action) {
        try {
            return CurrentIdentity.requireAdmin();
        } catch (InternalIdentityException e) {
            log.info("ADMIN_ACTION_REFUSED actor={} action={} target={} requestId={} status={}", actor(),
                    clean(action), NONE, requestId(), e.getStatusCode().value());
            throw e;
        }
    }

    private static String actor() {
        return CurrentIdentity.get().map(identity -> identity.isAnonymous() ? "anonymous" : clean(identity.subject()))
                .orElse(NONE);
    }

    private static String requestId() {
        return clean(MDC.get(RequestIdFilter.MDC_KEY));
    }

    static String clean(Object value) {
        if (value == null) {
            return NONE;
        }
        String text = UNSAFE.matcher(value.toString()).replaceAll("_");
        if (text.isEmpty()) {
            return NONE;
        }
        return text.length() <= MAX_VALUE ? text : text.substring(0, MAX_VALUE);
    }
}
