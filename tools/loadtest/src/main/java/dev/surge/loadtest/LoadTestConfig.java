package dev.surge.loadtest;

import java.net.URI;
import java.nio.file.Path;

public record LoadTestConfig(int users, long eventId, URI baseUrl, Path outDir, boolean reset,
                             double hotShare, int maxAttempts) {

    public static final String USAGE = """
        Usage: java -jar loadtest.jar [options]
          --users <n>          concurrent buyers (default 10000)
          --event <id>         event id (default 1)
          --base-url <url>     booking service URL (default http://localhost:8080)
          --out <dir>          report directory (default reports)
          --no-reset           do not reset the event before the run
          --hot-share <0..1>   share of picks aimed at hot seats (default 0.7)
          --max-attempts <n>   hold attempts per buyer (default 10)""";

    public static LoadTestConfig parse(String[] args) {
        int users = 10000;
        long event = 1;
        URI base = URI.create("http://localhost:8080");
        Path out = Path.of("reports");
        boolean reset = true;
        double hot = 0.7;
        int attempts = 10;
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
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number: " + e.getMessage(), e);
        }
        if (users < 1 || attempts < 1 || hot < 0 || hot > 1) {
            throw new IllegalArgumentException("users/max-attempts must be >= 1 and hot-share within 0..1");
        }
        return new LoadTestConfig(users, event, base, out, reset, hot, attempts);
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Missing value for " + args[i - 1]);
        }
        return args[i];
    }
}
