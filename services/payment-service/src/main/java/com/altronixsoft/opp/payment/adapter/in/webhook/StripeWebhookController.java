package com.altronixsoft.opp.payment.adapter.in.webhook;

import com.altronixsoft.opp.payment.application.InvalidWebhookException;
import com.altronixsoft.opp.payment.application.LiveModeWebhookException;
import com.altronixsoft.opp.payment.application.ReceiveWebhookService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /webhooks/stripe} (architecture §6.6, §8.3, ADR-0009). No token: the request is authenticated by its
 * {@code Stripe-Signature}, which is computed over the exact bytes Stripe sent, so the body is read raw from the request
 * before anything parses it.
 *
 * <ul>
 *   <li>body larger than 256 KB → 413, nothing read further;
 *   <li>missing, wrong or expired signature, or not an event → 400, nothing stored, {@code webhook.signature.failures}
 *       (F12);
 *   <li>live-mode event → 400, ERROR log, {@code webhook.livemode.rejected} (F13);
 *   <li>otherwise → stored (once: a redelivery is acknowledged without effect, F09) and 200, without any business logic.
 * </ul>
 *
 * The payload and the signature are never logged.
 */
@RestController
class StripeWebhookController {

    static final String PATH = "/webhooks/stripe";
    static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final ReceiveWebhookService service;
    private final MeterRegistry meters;

    StripeWebhookController(ReceiveWebhookService service, MeterRegistry meters) {
        this.service = service;
        this.meters = meters;
    }

    @PostMapping(PATH)
    ResponseEntity<?> receive(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_PAYLOAD_BYTES) {
            return problem(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large", "The webhook body exceeds 256 KB");
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_PAYLOAD_BYTES + 1);
        }
        if (body.length > MAX_PAYLOAD_BYTES) {
            return problem(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large", "The webhook body exceeds 256 KB");
        }
        String payload = new String(body, StandardCharsets.UTF_8);
        try {
            ReceiveWebhookService.Received received = service.receive(payload, request.getHeader("Stripe-Signature"));
            meters.counter("webhook.received", "type", received.type()).increment();
            if (received.duplicate()) {
                meters.counter("webhook.duplicates").increment();
                log.info(
                        "Webhook {} ({}) was already received; acknowledged again",
                        received.eventId(),
                        received.type());
            } else {
                log.debug("Webhook {} ({}) stored", received.eventId(), received.type());
            }
            return ResponseEntity.ok().build();
        } catch (InvalidWebhookException e) {
            meters.counter(
                            "webhook.signature.failures",
                            "reason",
                            e.reason().name().toLowerCase(java.util.Locale.ROOT))
                    .increment();
            log.warn("Webhook refused ({}): {}", e.reason(), e.getMessage());
            return problem(HttpStatus.BAD_REQUEST, "invalid-webhook", "The webhook could not be verified");
        } catch (LiveModeWebhookException e) {
            meters.counter("webhook.livemode.rejected").increment();
            log.error(
                    "Live-mode webhook {} ({}) refused: this system must only ever receive test-mode events. Check "
                            + "which Stripe endpoint points here.",
                    e.eventId(),
                    e.type());
            return problem(HttpStatus.BAD_REQUEST, "livemode-webhook", "Live-mode events are not accepted");
        }
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:problem-type:" + code));
        problem.setProperty("code", code);
        problem.setInstance(URI.create(PATH));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
