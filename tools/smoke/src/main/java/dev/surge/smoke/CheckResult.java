package dev.surge.smoke;

public record CheckResult(String name, boolean passed, String detail, long millis) {
}
