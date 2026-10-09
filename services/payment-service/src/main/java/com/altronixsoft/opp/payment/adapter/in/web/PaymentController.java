package com.altronixsoft.opp.payment.adapter.in.web;

import com.altronixsoft.opp.payment.application.GetPaymentService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payments API (architecture §11). Who may call is decided in the security filter chain (roles); whose payment a caller may
 * see is decided by the use case, which answers 404 for payments that are not theirs.
 */
@RestController
@RequestMapping(path = "/api/v1/payments", produces = "application/json")
class PaymentController {

    private final GetPaymentService getPayment;

    PaymentController(GetPaymentService getPayment) {
        this.getPayment = getPayment;
    }

    /**
     * The payment of an order: status and amount, and for the paying customer, while it is payable, the client secret
     * fetched from Stripe on demand. {@code Cache-Control: no-store}: the response may carry a secret.
     */
    @GetMapping("/by-order/{orderId}")
    ResponseEntity<PaymentResponse> byOrder(@PathVariable UUID orderId, Authentication authentication) {
        PaymentResponse body = PaymentResponse.of(getPayment.getByOrder(orderId, Callers.from(authentication)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
