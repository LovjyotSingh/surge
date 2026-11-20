package dev.surge.smoke;

import java.net.URI;
import java.util.function.UnaryOperator;

/** {@code adminPassword} is never printed, see {@link #toString()}. */
public record SmokeConfig(URI baseUrl, URI mailUrl, String adminEmail, String adminPassword, long eventId) {

    public static final String PASSWORD_ENV = "SURGE_ADMIN_PASSWORD";

    public static final String USAGE = """
        Usage: java -jar smoke.jar [options]
          --base-url <url>       nginx / gateway URL (default $SURGE_BASE_URL or http://127.0.0.1:8080)
          --mail-url <url>       Mailpit API (default $SURGE_MAIL_URL or http://127.0.0.1:8025)
          --admin-email <email>  admin account (default $SURGE_ADMIN_EMAIL or admin@surge.local)
          --admin-password <pw>  admin password (default: $SURGE_ADMIN_PASSWORD; required. Prefer the variable:
                                 a flag shows up in `ps`)
          --event <id>           event the checks buy seats for, and reset at the end (default 1)""";

    public static SmokeConfig parse(String[] args) {
        return parse(args, System::getenv);
    }

    /** {@code env} looks up an environment variable (null when unset); a seam for tests. */
    public static SmokeConfig parse(String[] args, UnaryOperator<String> env) {
        String base = orDefault(env.apply("SURGE_BASE_URL"), "http://127.0.0.1:8080");
        String mail = orDefault(env.apply("SURGE_MAIL_URL"), "http://127.0.0.1:8025");
        String email = orDefault(env.apply("SURGE_ADMIN_EMAIL"), "admin@surge.local");
        String password = orDefault(env.apply(PASSWORD_ENV), null);
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
            throw new IllegalArgumentException("Set " + PASSWORD_ENV + " or pass --admin-password");
        }
        return new SmokeConfig(uri(base), uri(mail), email, password, event);
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
