package dev.surge.loadtest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/** {@code adminPassword} may be null (nothing supplied); it is never printed, see {@link #toString()}. */
public record LoadTestConfig(int users, long eventId, URI baseUrl, Path outDir, boolean reset,
                             double hotShare, int maxAttempts, String adminEmail, String adminPassword) {

    public static final String PASSWORD_ENV = "SURGE_ADMIN_PASSWORD";
    public static final String PASSWORD_FILE_ENV = "SURGE_ADMIN_PASSWORD_FILE";

    public static final String USAGE = """
        Usage: java -jar loadtest.jar [options]
          --users <n>            concurrent buyers (default 10000)
          --event <id>           event id (default 1)
          --base-url <url>       gateway URL (default http://localhost:8080)
          --out <dir>            report directory (default reports)
          --no-reset             do not reset the event before the run
          --hot-share <0..1>     share of picks aimed at hot seats (default 0.7)
          --max-attempts <n>     hold attempts per buyer (default 10)
          --admin-email <email>  admin account used to mint buyer tokens (default admin@surge.local)
          --admin-password <pw>  admin password (default: the SURGE_ADMIN_PASSWORD environment variable, or
                                 the file SURGE_ADMIN_PASSWORD_FILE names; required, there is no built-in default)""";

    public static LoadTestConfig parse(String[] args) {
        return parse(args, System::getenv);
    }

    /** {@code env} looks up an environment variable (null when unset); a seam for tests. */
    public static LoadTestConfig parse(String[] args, UnaryOperator<String> env) {
        int users = 10000;
        long event = 1;
        URI base = URI.create("http://localhost:8080");
        Path out = Path.of("reports");
        boolean reset = true;
        double hot = 0.7;
        int attempts = 10;
        String email = "admin@surge.local";
        String password = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--users" -> users = Integer.parseInt(value(args, ++i));
                    case "--event" -> event = Long.parseLong(value(args, ++i));
                    case "--base-url" -> base = URI.create(value(args, ++i));
                    case "--out" -> out = Path.of(value(args, ++i));
                    case "--no-reset" -> reset = false;
                    case "--hot-share" -> hot = Double.parseDouble(value(args, ++i));
                    case "--max-attempts" -> attempts = Integer.parseInt(value(args, ++i));
                    case "--admin-email" -> email = value(args, ++i);
                    case "--admin-password" -> password = value(args, ++i);
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number: " + e.getMessage(), e);
        }
        if (users < 1 || attempts < 1 || hot < 0 || hot > 1) {
            throw new IllegalArgumentException("users/max-attempts must be >= 1 and hot-share within 0..1");
        }
        if (password == null || password.isEmpty()) {
            password = passwordFromEnvironment(env);
        }
        return new LoadTestConfig(users, event, base, out, reset, hot, attempts, email, password);
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

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[i - 1]);
        }
        return args[i];
    }

    @Override
    public String toString() {
        return "LoadTestConfig[users=" + users + ", eventId=" + eventId + ", baseUrl=" + baseUrl
            + ", adminEmail=" + adminEmail + ", adminPassword=" + (adminPassword == null ? "unset" : "***") + "]";
    }
}
