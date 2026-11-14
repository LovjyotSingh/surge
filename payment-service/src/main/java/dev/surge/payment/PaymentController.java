package dev.surge.payment;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
class PaymentController {
    private final PaymentStore store;

    PaymentController(PaymentStore store) {
        this.store = store;
    }

    @PostMapping
    ChargeResult charge(@Valid @RequestBody ChargeRequest req) {
        return store.charge(req);
    }

    @PostMapping("/{paymentId}/refund")
    Map<String, Boolean> refund(@PathVariable String paymentId) {
        store.refund(paymentId);
        return Map.of("refunded", true);
    }
}
