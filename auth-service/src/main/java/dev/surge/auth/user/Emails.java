package dev.surge.auth.user;

import java.util.Locale;
import java.util.regex.Pattern;

/** The one place an email is normalised and shape-checked, shared by signup, login and the admin seeder. */
public final class Emails {
    private static final Pattern SHAPE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private Emails() {
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** A basic local@domain.tld shape, no whitespace, at most 254 characters. */
    public static boolean looksLikeEmail(String email) {
        return email != null && email.length() <= 254 && SHAPE.matcher(email).matches();
    }
}
