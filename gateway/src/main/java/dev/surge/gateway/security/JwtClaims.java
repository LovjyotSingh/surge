package dev.surge.gateway.security;

import dev.surge.common.security.InternalTokens;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Strict reading of the claims the gateway trusts. Anything malformed is treated as absent, never guessed at: a
 * {@code roles} claim that is not a JSON array grants nothing, a role that is not an exact upper-case token grants
 * nothing, and an {@code X-User-Id}/{@code X-User-Email} that downstream could not parse safely is never emitted.
 * The user-id and role rules are common's ({@link InternalTokens#USER_ID}, {@link InternalTokens#ROLE}): exactly what
 * the gateway accepts here is what it can sign into {@code X-Surge-Identity}, and services verify the same rules.
 */
final class JwtClaims {

    static final Pattern USER_ID = InternalTokens.USER_ID;
    private static final Pattern ROLE = InternalTokens.ROLE;
    private static final Pattern EMAIL = Pattern.compile("^[\\x21-\\x7E]{1,254}$");

    private JwtClaims() {
    }

    static boolean validUserId(String sub) {
        return sub != null && USER_ID.matcher(sub).matches();
    }

    /** Synthetic load-test identity: a {@code lt-} subject or any {@code lt} claim, whatever its value or type. */
    static boolean isLoadtest(Jwt jwt) {
        String sub = jwt.getSubject();
        return (sub != null && sub.startsWith("lt-")) || jwt.hasClaim("lt");
    }

    /** Exact role tokens from the {@code roles} array; a plain string such as {@code "admin"} yields nothing. */
    static List<String> roles(Jwt jwt) {
        Object claim = jwt.getClaim("roles");
        List<String> roles = new ArrayList<>();
        if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof String role && ROLE.matcher(role).matches() && !roles.contains(role)) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }

    /** The email claim when it is a plain printable-ASCII string; otherwise null (header is then not set). */
    static String email(Jwt jwt) {
        Object claim = jwt.getClaim("email");
        return claim instanceof String email && EMAIL.matcher(email).matches() ? email : null;
    }
}
