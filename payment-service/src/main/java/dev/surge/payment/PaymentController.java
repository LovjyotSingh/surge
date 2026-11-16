package dev.surge.payment;

import dev.surge.common.security.CurrentIdentity;
import dev.surge.common.security.InternalTokens;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payments API, for booking-service only: payment's identity chain lets nothing but {@code svc:booking} in
 * ({@code AccessRulesConfig}), and every method checks again ({@link CurrentIdentity#requireService}).
 */
@RestController
@RequestMapping("/api/payments")
class PaymentController {
    private final PaymentStore store;

    PaymentController(PaymentStore store) {
        this.store = store;
    }

    @PostMapping
    ChargeResult charge(@Valid @RequestBody ChargeRequest req) {
        CurrentIdentity.requireService(InternalTokens.SVC_BOOKING);
        return store.charge(req);
    }

    @GetMapping
    PaymentStatus find(@RequestParam String reference) {
        CurrentIdentity.requireService(InternalTokens.SVC_BOOKING);
        return store.find(reference);
    }

    @PostMapping("/{paymentId}/refund")
    Map<String, Boolean> refund(@PathVariable String paymentId) {
        CurrentIdentity.requireService(InternalTokens.SVC_BOOKING);
        store.refund(paymentId);
        return Map.of("refunded", true);
    }
}
