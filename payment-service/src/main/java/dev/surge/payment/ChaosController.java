package dev.surge.payment;

import dev.surge.payment.error.ForbiddenException;
import dev.surge.payment.error.InvalidBodyException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The gateway also requires ADMIN here; this check is defence in depth. The role check runs before validation. */
@RestController
@RequestMapping("/api/payments/admin/chaos")
class ChaosController {
    private final ChaosSettings settings;
    private final Validator validator;

    ChaosController(ChaosSettings settings, Validator validator) {
        this.settings = settings;
        this.validator = validator;
    }

    @GetMapping
    Chaos get(@RequestHeader(value = "X-User-Roles", required = false) String roles) {
        requireAdmin(roles);
        return settings.get();
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void put(@RequestHeader(value = "X-User-Roles", required = false) String roles,
             @RequestBody Chaos chaos) {
        requireAdmin(roles);
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<Chaos> v : validator.validate(chaos)) {
            errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new InvalidBodyException(errors);
        }
        settings.set(chaos);
    }

    private static void requireAdmin(String roles) {
        if (roles == null || Arrays.stream(roles.split(",")).map(String::trim).noneMatch("ADMIN"::equals)) {
            throw new ForbiddenException();
        }
    }
}
