package dev.surge.auth.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TestTokenRequest(
        @NotNull @Pattern(regexp = "^lt-[A-Za-z0-9]{1,16}$", message = "must match lt-[A-Za-z0-9]{1,16}") String prefix,
        @Min(1) @Max(20000) int count) {
}
