package dev.surge.smoke;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/** {@code adminPassword} is never printed, see {@link #toString()}. */
public record SmokeConfig(URI baseUrl, URI mailUrl, String adminEmail, String adminPassword, long eventId) {

    public static final String PASSWORD_ENV = "SURGE_ADMIN_PASSWORD";
    public static final String PASSWORD_FILE_ENV = "SURGE_ADMIN_PASSWORD_FILE";

    public static final String USAGE = """
        Usage: java -jar smoke.jar [options]
          --base-url <url>       nginx / gateway URL (default $SURGE_BASE_URL or http://127.0.0.1:8080)
          --mail-url <url>       Mailpit API (default $SURGE_MAIL_URL or http://127.0.0.1:8025)
          --admin-email <email>  admin account (default $SURGE_ADMIN_EMAIL or admin@surge.local)
          --admin-password <pw>  admin password (default: $SURGE_ADMIN_PASSWORD, or the file that
                                 $SURGE_ADMIN_PASSWORD_FILE names; required. Prefer either: a flag shows up in `ps`)
          --event <id>           event the checks buy seats for, and reset at the end (default 1)""";

    public static SmokeConfig parse(String[] args) {
        return parse(args, System::getenv);
    }

    /** {@code env} looks up an environment variable (null when unset); a seam for tests. */
    public static SmokeConfig parse(String[] args, UnaryOperator<String> env) {
        String base = orDefault(env.apply("SURGE_BASE_URL"), "http://127.0.0.1:8080");
        String mail = orDefault(env.apply("SURGE_MAIL_URL"), "http://127.0.0.1:8025");
        String email = orDefault(env.apply("SURGE_ADMIN_EMAIL"), "admin@surge.local");
        String password = null;
        long event = 1;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--base-url" -> base = value(args, ++i);
                    case "--mail-url" -> mail = value(args, ++i);
                    case "--admin-email" -> email = value(args, ++i);
                    case "--admin-password" -> password = value(args, ++i);
                    case "--event" -> event = Long.parseLong(value(args, ++i));
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number: " + e.getMessage(), e);
        }
        if (password == null || password.isEmpty()) {
            password = passwordFromEnvironment(env);
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Set " + PASSWORD_ENV + " or pass --admin-password");
        }
        return new SmokeConfig(uri(base), uri(mail), email, password, event);
    }

    /**
     * {@value #PASSWORD_ENV}, or the file named by {@value #PASSWORD_FILE_ENV} (compose mounts the admin password as a
     * secret file: S10), one trailing newline dropped; null when neither is set. Both at once is refused. Messages
     * never contain a value.
     */
    static String passwordFromEnvironment(UnaryOperator<String> env) {
        String value = env.apply(PASSWORD_ENV);
        String file = env.apply(PASSWORD_FILE_ENV);
        boolean hasValue = value != null && !value.isEmpty();
        if (file == null || file.isEmpty()) {
            return hasValue ? value : null;
        }
        if (hasValue) {
            throw new IllegalArgumentException("Set " + PASSWORD_ENV + " or " + PASSWORD_FILE_ENV + ", not both");
        }
        try {
            String content = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            content = content.endsWith("\r\n") ? content.substring(0, content.length() - 2)
                : content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
            return content.isEmpty() ? null : content;
        } catch (IOException | InvalidPathException e) {
            throw new IllegalArgumentException("Cannot read the file named by " + PASSWORD_FILE_ENV, e);
        }
    }

    private static URI uri(String s) {
        return URI.create(s.endsWith("/") ? s.substring(0, s.length() - 1) : s);
    }

    private static String orDefault(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[i - 1]);
        }
        return args[i];
    }

    @Override
    public String toString() {
        return "SmokeConfig[baseUrl=" + baseUrl + ", mailUrl=" + mailUrl + ", adminEmail=" + adminEmail
            + ", adminPassword=***, eventId=" + eventId + "]";
    }
}
