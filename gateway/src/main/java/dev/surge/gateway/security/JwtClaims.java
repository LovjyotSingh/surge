package dev.surge.gateway.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Strict reading of the claims the gateway trusts. Anything malformed is treated as absent, never guessed at: a
 * {@code roles} claim that is not a JSON array grants nothing, a role that is not an exact upper-case token grants
 * nothing, and an {@code X-User-Id}/{@code X-User-Email} that downstream could not parse safely is never emitted.
 */
final class JwtClaims {

    static final Pattern USER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z_]{0,31}$");
    private static final Pattern EMAIL = Pattern.compile("^[\\x21-\\x7E]{1,254}$");

    private JwtClaims() {
    }

    static boolean validUserId(String sub) {
        return sub != null && USER_ID.matcher(sub).matches();
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
