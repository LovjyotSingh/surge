package dev.surge.payment;

import dev.surge.common.security.AdminAudit;
import dev.surge.common.security.CurrentIdentity;
import dev.surge.payment.error.InvalidBodyException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gateway and payment's identity chain already require a gateway ADMIN here, and so does payment's admin
 * interceptor ({@code WebConfig}) before the body is bound; the {@link CurrentIdentity} check is defence in depth and
 * runs before validation. Each read or change writes one {@code ADMIN_ACTION} line ({@link AdminAudit}), never the
 * values.
 */
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
    Chaos get() {
        AdminAudit.requireAdmin("payment.chaos.get");
        Chaos current = settings.get();
        AdminAudit.log("payment.chaos.get", "chaos");
        return current;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void put(@RequestBody Chaos chaos) {
        AdminAudit.requireAdmin("payment.chaos.put");
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<Chaos> v : validator.validate(chaos)) {
            errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new InvalidBodyException(errors);
        }
        settings.set(chaos);
        AdminAudit.log("payment.chaos.put", "chaos");
    }
}
