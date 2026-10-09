package com.altronixsoft.opp.payment.adapter.in.web;

import com.altronixsoft.opp.payment.application.Caller;
import com.altronixsoft.opp.payment.application.ConfirmTestPaymentService;
import com.altronixsoft.opp.payment.application.TestScenario;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test support (architecture §8.5): confirms the PaymentIntent of the caller's payment with a Stripe test payment method, in
 * place of the browser, so that the demo and the tests can pay without Stripe.js.
 *
 * <p>This bean exists only when {@code platform.test-support.enabled=true} (the {@code local} and {@code stripe-test}
 * profiles); otherwise there is no such endpoint at all. It changes nothing in the database: Stripe's webhook brings the
 * result, through the same pipeline as a real payment.
 */
@RestController
@RequestMapping(path = "/api/v1/test-support", produces = "application/json")
@ConditionalOnProperty(prefix = "platform.test-support", name = "enabled", havingValue = "true")
public class TestSupportController {

    private final ConfirmTestPaymentService confirmService;

    TestSupportController(ConfirmTestPaymentService confirmService) {
        this.confirmService = confirmService;
    }

    /**
     * @param scenario {@code success}, {@code decline}, {@code insufficient_funds}, {@code requires_3ds},
     *     {@code dispute} or {@code refund_fail}
     */
    @PostMapping("/payments/by-order/{orderId}/confirm")
    ResponseEntity<ConfirmResponse> confirm(
            @PathVariable UUID orderId, @RequestParam("scenario") String scenario, Authentication authentication) {
        TestScenario parsed = TestScenario.fromParameter(scenario)
                .orElseThrow(() -> new InvalidRequestParameterException(
                        "scenario",
                        "must be one of "
                                + Arrays.stream(TestScenario.values())
                                        .map(TestScenario::parameter)
                                        .collect(Collectors.joining(", "))));
        Caller caller = Callers.from(authentication);
        ConfirmTestPaymentService.Result result = confirmService.confirm(orderId, caller, parsed);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ConfirmResponse.of(result));
    }

    /**
     * What Stripe answered. The payment itself is not changed here; the outcome follows as a webhook.
     *
     * @param accepted whether Stripe took the confirmation ({@code false}: the card was refused, a decline webhook follows)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ConfirmResponse(
            String scenario, boolean accepted, String stripeStatus, String errorCode, String declineCode) {

        static ConfirmResponse of(ConfirmTestPaymentService.Result result) {
            return new ConfirmResponse(
                    result.scenario().parameter(),
                    !result.declined(),
                    result.stripeStatus(),
                    result.errorCode(),
                    result.declineCode());
        }
    }
}
