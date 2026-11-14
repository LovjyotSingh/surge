package dev.surge.payment;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ChargeRequest(
        @NotBlank @Size(max = 200) String reference,
        @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$") String userId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000000")
        @Digits(integer = 8, fraction = 2) BigDecimal amountInr) {
}
