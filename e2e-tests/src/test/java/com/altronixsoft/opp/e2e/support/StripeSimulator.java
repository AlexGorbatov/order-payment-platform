package com.altronixsoft.opp.e2e.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stripe, as far as the platform sees it: a WireMock server that answers the API calls of {@code payment-service}
 * (through the real Stripe SDK) from a small, stateful model, and a webhook sender that signs what the model emits.
 *
 * <p>What it models, because the platform's guarantees depend on it:
 *
 * <ul>
 *   <li><b>PaymentIntents and refunds with a life cycle.</b> Confirming with a test payment method moves a PaymentIntent
 *       the way Stripe does ({@code pm_card_visa} succeeds, {@code pm_card_chargeDeclined} is refused,
 *       {@code pm_card_authenticationRequired} asks for 3DS, ...); cancelling a succeeded one is refused with
 *       {@code payment_intent_unexpected_state}.
 *   <li><b>Idempotency keys.</b> The same key with the same request returns the response stored the first time, even if
 *       that response never reached the caller; the same key with other parameters is an {@code idempotency_error}.
 *       Without this the "exactly one PaymentIntent" scenarios would prove nothing.
 *   <li><b>Webhooks.</b> Every state change emits the events Stripe emits. They are delivered at once, held for the test
 *       to release (or reorder), dropped (a lost webhook), or delivered several times at once, always with a valid
 *       {@code Stripe-Signature}. A delivery that fails is retried, as Stripe does.
 * </ul>
 *
 * Everything is in memory and lives for the JVM; tests identify their objects by order id, so nothing needs a reset
 * except the knobs ({@link #reset()}).
 */
public final class StripeSimulator implements AutoCloseable {

    /** The API operations a test can slow down. */
    public enum Operation {
        CREATE_PAYMENT_INTENT,
        RETRIEVE_PAYMENT_INTENT,
        CONFIRM_PAYMENT_INTENT,
        CANCEL_PAYMENT_INTENT,
        CREATE_REFUND
    }

    /** What happens to the webhooks the simulator emits. */
    public enum WebhookMode {
        /** Delivered right away, in the order they were emitted. */
        AUTO,
        /** Kept until {@link #releaseHeldWebhooks()} or {@link #deliver(SimEvent)}. */
        HOLD,
        /** Never delivered (the endpoint was down for good): the loss reconciliation exists for. */
        DROP
    }

    /** One Stripe webhook event as the simulator emitted it. */
    public record SimEvent(String id, String type, String paymentIntentId, Instant created, String payload) {}

    /** A PaymentIntent as it stands now. */
    public record PaymentIntentView(
            String id,
            UUID orderId,
            UUID paymentId,
            long amountMinor,
            String currency,
            String status,
            String paymentMethod,
            String clientSecret,
            long amountRefundedMinor) {}

    /** A refund as it stands now. */
    public record RefundView(
            String id, String paymentIntentId, UUID refundId, long amountMinor, String status, String failureReason) {}

    /** One API call as the simulator saw it. */
    public record ApiCall(
            Operation operation, String path, String idempotencyKey, boolean replayed, int status, UUID orderId) {}

    private static final String SECRET = "whsec_e2e_simulated";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern PATH =
            Pattern.compile("/v1/(payment_intents|refunds)(?:/([^/]+))?(?:/(confirm|cancel))?");

    private final WireMockServer server;
    private final WebhookSender webhooks;
    private final AtomicLong sequence = new AtomicLong(1000);

    // ---- state, guarded by this ----
    private final Map<String, PaymentIntent> paymentIntents = new LinkedHashMap<>();
    private final Map<String, Refund> refunds = new LinkedHashMap<>();
    private final Map<String, StoredResponse> idempotent = new LinkedHashMap<>();
    private final List<ApiCall> calls = new ArrayList<>();
    private final List<SimEvent> events = new ArrayList<>();
    private final List<SimEvent> held = new ArrayList<>();
    private final Map<Operation, Duration> responseDelays = new LinkedHashMap<>();
    private WebhookMode mode = WebhookMode.AUTO;
    private Instant lastEventCreated = Instant.EPOCH;

    StripeSimulator(java.util.function.Supplier<String> webhookUrl) {
        this.webhooks = new WebhookSender(webhookUrl, SECRET);
        this.server = new WireMockServer(
                WireMockConfiguration.wireMockConfig().dynamicPort().extensions(new SimulatorTransformer(this)));
        this.server.start();
        this.server.stubFor(
                any(urlPathMatching("/v1/.*")).willReturn(aResponse().withTransformers(SimulatorTransformer.NAME)));
    }

    /** {@code stripe.api-base} of the service under test. */
    public String baseUrl() {
        return server.baseUrl();
    }

    /** The signing secret the service must be configured with ({@code STRIPE_WEBHOOK_SECRET}). */
    public static String webhookSecret() {
        return SECRET;
    }

    @Override
    public void close() {
        webhooks.close();
        server.stop();
    }

    // ============================================================================================ knobs

    /** Back to normal operation: webhooks delivered at once, once each, no slow responses. State is kept. */
    public synchronized void reset() {
        mode = WebhookMode.AUTO;
        held.clear();
        responseDelays.clear();
        webhooks.setCopies(1);
    }

    public synchronized void webhooks(WebhookMode newMode) {
        mode = newMode;
    }

    /** Every webhook is POSTed {@code copies} times, concurrently (Stripe delivers at least once). */
    public void webhookCopies(int copies) {
        webhooks.setCopies(copies);
    }

    /**
     * Answers {@code operation} {@code delay} after it has been carried out: the effect is there, the caller waits for
     * the news. With a service killed in that window this is the crash between Stripe's answer and the local commit
     * (F05); with a delay above the client's read timeout it is a timeout (F04).
     */
    public synchronized void delayResponses(Operation operation, Duration delay) {
        if (delay.isZero()) {
            responseDelays.remove(operation);
        } else {
            responseDelays.put(operation, delay);
        }
    }

    // ============================================================================================ what a test can ask

    public synchronized List<PaymentIntentView> paymentIntentsOf(UUID orderId) {
        return paymentIntents.values().stream()
                .filter(pi -> pi.orderId.equals(orderId))
                .map(PaymentIntent::view)
                .toList();
    }

    /** The one PaymentIntent of the order; fails if there is none or more than one. */
    public synchronized PaymentIntentView paymentIntentOf(UUID orderId) {
        List<PaymentIntentView> found = paymentIntentsOf(orderId);
        if (found.size() != 1) {
            throw new AssertionError("Expected exactly one PaymentIntent for order " + orderId + ", found " + found);
        }
        return found.get(0);
    }

    public synchronized List<PaymentIntentView> allPaymentIntents() {
        return paymentIntents.values().stream().map(PaymentIntent::view).toList();
    }

    public synchronized List<RefundView> refundsOf(String paymentIntentId) {
        return refunds.values().stream()
                .filter(r -> r.paymentIntentId.equals(paymentIntentId))
                .map(Refund::view)
                .toList();
    }

    public synchronized List<ApiCall> calls() {
        return List.copyOf(calls);
    }

    public synchronized List<ApiCall> calls(Operation operation) {
        return calls.stream().filter(c -> c.operation() == operation).toList();
    }

    /** The calls of {@code operation} that concerned the order, in the order they were made. */
    public synchronized List<ApiCall> calls(Operation operation, UUID orderId) {
        return calls.stream()
                .filter(c -> c.operation() == operation && orderId.equals(c.orderId()))
                .toList();
    }

    /** Calls of {@code operation} that were answered from an idempotency key already used. */
    public synchronized long replays(Operation operation) {
        return calls.stream()
                .filter(c -> c.operation() == operation && c.replayed())
                .count();
    }

    /** Every event emitted so far for the PaymentIntent, oldest first. */
    public synchronized List<SimEvent> eventsOf(String paymentIntentId) {
        return events.stream()
                .filter(e -> paymentIntentId.equals(e.paymentIntentId()))
                .toList();
    }

    public synchronized List<SimEvent> heldWebhooks() {
        return List.copyOf(held);
    }

    // ============================================================================================ what the customer
    // does

    /**
     * Confirms the PaymentIntent with a test payment method, as Stripe.js would in the customer's browser. The service's
     * test-support endpoint ends up here through the API; a test may also call it directly.
     */
    public synchronized void confirm(String paymentIntentId, String paymentMethod) {
        Reply reply = confirmInternal(paymentIntent(paymentIntentId), paymentMethod);
        if (reply.status() != 200 && reply.status() != 402) {
            throw new AssertionError("Cannot confirm " + paymentIntentId + ": " + reply.body());
        }
    }

    /** The customer finishes (or fails) the 3DS challenge of a PaymentIntent in {@code requires_action}. */
    public synchronized void completeAuthentication(String paymentIntentId, boolean success) {
        PaymentIntent pi = paymentIntent(paymentIntentId);
        if (!pi.status.equals("requires_action")) {
            throw new AssertionError(paymentIntentId + " is " + pi.status + ", not requires_action");
        }
        if (success) {
            succeed(pi);
        } else {
            pi.status = "requires_payment_method";
            pi.lastError = new PaymentError(
                    "card_error",
                    "payment_intent_authentication_failure",
                    null,
                    "The provided PaymentMethod has failed authentication.");
            emit("payment_intent.payment_failed", pi.id, pi.object());
        }
    }

    /** A refund that was {@code pending} ends: the money is back, or the bank returned it. */
    public synchronized void settleRefund(String refundId, boolean success) {
        Refund refund = refunds.get(refundId);
        if (refund == null || !refund.status.equals("pending")) {
            throw new AssertionError("No pending refund " + refundId + ": " + (refund == null ? null : refund.view()));
        }
        if (success) {
            refund.status = "succeeded";
            emitChargeRefunded(paymentIntent(refund.paymentIntentId), refund);
        } else {
            refund.status = "failed";
            refund.failureReason = "expired_or_canceled_card";
            emit("refund.failed", refund.paymentIntentId, refund.object(paymentIntent(refund.paymentIntentId)));
        }
    }

    // ============================================================================================ webhooks

    /** Delivers everything held, in the order it was emitted. */
    public void releaseHeldWebhooks() {
        List<SimEvent> toSend;
        synchronized (this) {
            toSend = List.copyOf(held);
            held.clear();
        }
        toSend.forEach(webhooks::enqueue);
    }

    /** Delivers one event, now, whatever its mode or whether it was delivered before (a redelivery). */
    public void deliver(SimEvent event) {
        synchronized (this) {
            held.remove(event);
        }
        webhooks.enqueue(event);
    }

    /** Blocks until every webhook handed over so far has been acknowledged (or given up on). */
    public boolean awaitWebhooksDelivered(Duration timeout) {
        return webhooks.awaitIdle(timeout);
    }

    /** The webhook endpoint's answers so far: status codes of every delivery attempt. */
    public List<Integer> deliveryStatuses() {
        return webhooks.statuses();
    }

    // ============================================================================================ API (called by
    // WireMock)

    synchronized Reply handle(Request request) {
        String url = request.getUrl();
        String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        Matcher m = PATH.matcher(path);
        if (!m.matches()) {
            return error(404, "invalid_request_error", "resource_missing", "Unrecognized request URL: " + path);
        }
        String resource = m.group(1);
        String id = m.group(2);
        String action = m.group(3);
        String method = request.getMethod().getName();
        Operation operation = operation(method, resource, id, action);
        if (operation == null) {
            return error(
                    404,
                    "invalid_request_error",
                    "resource_missing",
                    "Unrecognized request URL: " + method + " " + path);
        }

        Map<String, String> form = form(request.getBodyAsString());
        String key = request.getHeader("Idempotency-Key");
        boolean mutating = !method.equals("GET");
        String fingerprint = method + " " + path + " " + new java.util.TreeMap<>(form);

        Reply reply;
        boolean replayed = false;
        if (mutating && key != null) {
            StoredResponse stored = idempotent.get(key);
            if (stored != null) {
                replayed = true;
                reply = stored.fingerprint().equals(fingerprint)
                        ? stored.reply()
                        : error(
                                400,
                                "idempotency_error",
                                null,
                                "Keys for idempotent requests can only be used with the same parameters they were first used with.");
            } else {
                reply = execute(operation, id, form);
                if (reply.status() < 500) {
                    idempotent.put(key, new StoredResponse(fingerprint, reply));
                }
            }
        } else {
            reply = execute(operation, id, form);
        }
        calls.add(new ApiCall(operation, path, key, replayed, reply.status(), orderIdOf(operation, id, form)));
        Duration delay = responseDelays.get(operation);
        return delay == null ? reply : reply.after(delay);
    }

    /** The order a call is about, for the journal: from the metadata of a create, else from the PaymentIntent it names. */
    private UUID orderIdOf(Operation operation, String id, Map<String, String> form) {
        if (operation == Operation.CREATE_PAYMENT_INTENT) {
            String orderId = form.get("metadata[orderId]");
            return orderId == null ? null : UUID.fromString(orderId);
        }
        PaymentIntent pi = paymentIntents.get(operation == Operation.CREATE_REFUND ? form.get("payment_intent") : id);
        return pi == null ? null : pi.orderId;
    }

    private static Operation operation(String method, String resource, String id, String action) {
        if (resource.equals("refunds")) {
            return method.equals("POST") && id == null ? Operation.CREATE_REFUND : null;
        }
        if (id == null) {
            return method.equals("POST") ? Operation.CREATE_PAYMENT_INTENT : null;
        }
        if (action == null) {
            return method.equals("GET") ? Operation.RETRIEVE_PAYMENT_INTENT : null;
        }
        if (!method.equals("POST")) {
            return null;
        }
        return action.equals("confirm") ? Operation.CONFIRM_PAYMENT_INTENT : Operation.CANCEL_PAYMENT_INTENT;
    }

    private Reply execute(Operation operation, String id, Map<String, String> form) {
        return switch (operation) {
            case CREATE_PAYMENT_INTENT -> createPaymentIntent(form);
            case RETRIEVE_PAYMENT_INTENT -> {
                PaymentIntent pi = paymentIntents.get(id);
                yield pi == null ? missing("payment_intent", id) : ok(pi.object());
            }
            case CONFIRM_PAYMENT_INTENT -> {
                PaymentIntent pi = paymentIntents.get(id);
                yield pi == null ? missing("payment_intent", id) : confirmInternal(pi, form.get("payment_method"));
            }
            case CANCEL_PAYMENT_INTENT -> {
                PaymentIntent pi = paymentIntents.get(id);
                yield pi == null ? missing("payment_intent", id) : cancel(pi);
            }
            case CREATE_REFUND -> createRefund(form);
        };
    }

    private Reply createPaymentIntent(Map<String, String> form) {
        long amount = Long.parseLong(form.getOrDefault("amount", "0"));
        String orderId = form.get("metadata[orderId]");
        String paymentId = form.get("metadata[paymentId]");
        if (amount <= 0 || form.get("currency") == null || orderId == null || paymentId == null) {
            return error(400, "invalid_request_error", "parameter_missing", "Missing required parameter");
        }
        String id = "pi_sim_" + sequence.incrementAndGet();
        PaymentIntent pi = new PaymentIntent(
                id,
                UUID.fromString(orderId),
                UUID.fromString(paymentId),
                amount,
                form.get("currency"),
                "pi_sim_" + id.substring(7) + "_secret_"
                        + UUID.randomUUID().toString().replace("-", ""),
                Instant.now());
        paymentIntents.put(id, pi);
        return ok(pi.object());
    }

    private Reply confirmInternal(PaymentIntent pi, String paymentMethod) {
        if (!pi.status.equals("requires_payment_method") && !pi.status.equals("requires_action")) {
            return unexpectedState(pi, "confirm");
        }
        if (paymentMethod == null) {
            return error(400, "invalid_request_error", "parameter_missing", "Missing payment_method");
        }
        pi.paymentMethod = paymentMethod;
        switch (paymentMethod) {
            case "pm_card_visa", "pm_card_refundFail" -> succeed(pi);
            case "pm_card_createDispute" -> {
                succeed(pi);
                emit("charge.dispute.created", pi.id, disputeOf(pi));
            }
            case "pm_card_authenticationRequired" -> {
                pi.status = "requires_action";
                emit("payment_intent.requires_action", pi.id, pi.object());
            }
            case "pm_card_chargeDeclined", "pm_card_chargeDeclinedInsufficientFunds" -> {
                String decline =
                        paymentMethod.equals("pm_card_chargeDeclined") ? "generic_decline" : "insufficient_funds";
                String message = decline.equals("generic_decline")
                        ? "Your card was declined."
                        : "Your card has insufficient funds.";
                pi.status = "requires_payment_method";
                pi.lastError = new PaymentError("card_error", "card_declined", decline, message);
                emit("payment_intent.payment_failed", pi.id, pi.object());
                return error(402, "card_error", "card_declined", decline, message);
            }
            default -> {
                return error(
                        400, "invalid_request_error", "resource_missing", "No such PaymentMethod: " + paymentMethod);
            }
        }
        return ok(pi.object());
    }

    private void succeed(PaymentIntent pi) {
        pi.status = "succeeded";
        pi.lastError = null;
        pi.chargeId = "ch_sim_" + pi.id.substring(7);
        emit("payment_intent.succeeded", pi.id, pi.object());
    }

    private Reply cancel(PaymentIntent pi) {
        if (!List.of("requires_payment_method", "requires_action", "requires_confirmation", "processing")
                .contains(pi.status)) {
            return unexpectedState(pi, "cancel");
        }
        pi.status = "canceled";
        emit("payment_intent.canceled", pi.id, pi.object());
        return ok(pi.object());
    }

    private Reply createRefund(Map<String, String> form) {
        PaymentIntent pi = paymentIntents.get(form.get("payment_intent"));
        if (pi == null) {
            return missing("payment_intent", form.get("payment_intent"));
        }
        long amount = Long.parseLong(form.getOrDefault("amount", Long.toString(pi.amountMinor)));
        if (!pi.status.equals("succeeded")) {
            return error(
                    400,
                    "invalid_request_error",
                    "charge_not_refundable",
                    "The PaymentIntent has not been paid, so there is nothing to refund.");
        }
        if (pi.amountRefundedMinor() + amount > pi.amountMinor) {
            return error(
                    400,
                    "invalid_request_error",
                    "charge_already_refunded",
                    "Charge " + pi.chargeId + " has already been refunded.");
        }
        String id = "re_sim_" + sequence.incrementAndGet();
        Refund refund = new Refund(
                id,
                pi.id,
                amount,
                pi.currency,
                form.get("metadata[refundId]"),
                form.get("metadata[paymentId]"),
                form.get("metadata[orderId]"));
        refunds.put(id, refund);
        if ("pm_card_refundFail".equals(pi.paymentMethod)) {
            // The bank takes its time and then returns the money: the outcome is up to settleRefund().
            refund.status = "pending";
        } else {
            refund.status = "succeeded";
            emitChargeRefunded(pi, refund);
        }
        return ok(refund.object(pi));
    }

    private Reply unexpectedState(PaymentIntent pi, String verb) {
        return error(
                400,
                "invalid_request_error",
                "payment_intent_unexpected_state",
                "You cannot " + verb + " this PaymentIntent because it has a status of " + pi.status + ".");
    }

    private Reply missing(String what, String id) {
        return error(404, "invalid_request_error", "resource_missing", "No such " + what + ": '" + id + "'");
    }

    private PaymentIntent paymentIntent(String id) {
        PaymentIntent pi = paymentIntents.get(id);
        if (pi == null) {
            throw new AssertionError("The simulator knows no PaymentIntent " + id);
        }
        return pi;
    }

    // ============================================================================================ events

    private void emitChargeRefunded(PaymentIntent pi, Refund refund) {
        refund.counted = true;
        emit("charge.refunded", pi.id, chargeOf(pi));
    }

    private void emit(String type, String paymentIntentId, JsonNode object) {
        SimEvent event = newEvent(type, paymentIntentId, object, nextEventTime());
        switch (mode) {
            case AUTO -> webhooks.enqueue(event);
            case HOLD -> held.add(event);
            case DROP -> {
                // lost on the way; stays in `events` so a test can see what the platform never heard of
            }
        }
    }

    /** Stripe stamps events with whole seconds; two events of one PaymentIntent are never in the same second here. */
    private Instant nextEventTime() {
        Instant now = Instant.ofEpochSecond(Instant.now().getEpochSecond());
        Instant next = now.isAfter(lastEventCreated) ? now : lastEventCreated.plusSeconds(1);
        lastEventCreated = next;
        return next;
    }

    private SimEvent newEvent(String type, String paymentIntentId, JsonNode object, Instant created) {
        ObjectNode event = JSON.createObjectNode();
        String id = "evt_sim_" + sequence.incrementAndGet();
        event.put("id", id);
        event.put("object", "event");
        event.put("api_version", "2026-09-30.endive");
        event.put("created", created.getEpochSecond());
        event.putObject("data").set("object", object);
        event.put("livemode", false);
        event.put("pending_webhooks", 1);
        event.putObject("request").putNull("id").putNull("idempotency_key");
        event.put("type", type);
        SimEvent simEvent = new SimEvent(id, type, paymentIntentId, created, JSON.writeValueAsString(event));
        events.add(simEvent);
        return simEvent;
    }

    private JsonNode chargeOf(PaymentIntent pi) {
        ObjectNode charge = JSON.createObjectNode();
        charge.put("id", pi.chargeId);
        charge.put("object", "charge");
        charge.put("amount", pi.amountMinor);
        charge.put("amount_refunded", pi.amountRefundedMinor());
        charge.put("currency", pi.currency);
        charge.put("paid", true);
        charge.put("refunded", pi.amountRefundedMinor() >= pi.amountMinor);
        charge.put("status", "succeeded");
        charge.put("payment_intent", pi.id);
        charge.put("livemode", false);
        charge.putObject("metadata");
        return charge;
    }

    private JsonNode disputeOf(PaymentIntent pi) {
        ObjectNode dispute = JSON.createObjectNode();
        dispute.put("id", "dp_sim_" + pi.id.substring(7));
        dispute.put("object", "dispute");
        dispute.put("amount", pi.amountMinor);
        dispute.put("currency", pi.currency);
        dispute.put("charge", pi.chargeId);
        dispute.put("payment_intent", pi.id);
        dispute.put("reason", "fraudulent");
        dispute.put("status", "needs_response");
        dispute.put("created", Instant.now().getEpochSecond());
        dispute.put("livemode", false);
        dispute.putObject("metadata");
        return dispute;
    }

    // ============================================================================================ model

    private record PaymentError(String type, String code, String declineCode, String message) {}

    private final class PaymentIntent {
        final String id;
        final UUID orderId;
        final UUID paymentId;
        final long amountMinor;
        final String currency;
        final String clientSecret;
        final Instant created;
        String status = "requires_payment_method";
        String paymentMethod;
        String chargeId;
        PaymentError lastError;

        PaymentIntent(
                String id,
                UUID orderId,
                UUID paymentId,
                long amountMinor,
                String currency,
                String clientSecret,
                Instant created) {
            this.id = id;
            this.orderId = orderId;
            this.paymentId = paymentId;
            this.amountMinor = amountMinor;
            this.currency = currency;
            this.clientSecret = clientSecret;
            this.created = created;
        }

        long amountRefundedMinor() {
            return refunds.values().stream()
                    .filter(r -> r.paymentIntentId.equals(id) && r.counted)
                    .mapToLong(r -> r.amountMinor)
                    .sum();
        }

        PaymentIntentView view() {
            return new PaymentIntentView(
                    id,
                    orderId,
                    paymentId,
                    amountMinor,
                    currency,
                    status,
                    paymentMethod,
                    clientSecret,
                    amountRefundedMinor());
        }

        ObjectNode object() {
            ObjectNode pi = JSON.createObjectNode();
            pi.put("id", id);
            pi.put("object", "payment_intent");
            pi.put("amount", amountMinor);
            pi.put("amount_received", status.equals("succeeded") ? amountMinor : 0);
            pi.put("currency", currency);
            pi.put("status", status);
            pi.put("client_secret", clientSecret);
            pi.put("created", created.getEpochSecond());
            if (chargeId != null) {
                pi.put("latest_charge", chargeId);
            }
            if (lastError == null) {
                pi.putNull("last_payment_error");
            } else {
                ObjectNode error = pi.putObject("last_payment_error");
                error.put("type", lastError.type());
                error.put("code", lastError.code());
                if (lastError.declineCode() != null) {
                    error.put("decline_code", lastError.declineCode());
                }
                error.put("message", lastError.message());
            }
            if (status.equals("canceled")) {
                pi.put("cancellation_reason", "abandoned");
            }
            if (status.equals("requires_action")) {
                pi.putObject("next_action").put("type", "use_stripe_sdk");
            }
            pi.put("livemode", false);
            ObjectNode metadata = pi.putObject("metadata");
            metadata.put("orderId", orderId.toString());
            metadata.put("paymentId", paymentId.toString());
            return pi;
        }
    }

    private final class Refund {
        final String id;
        final String paymentIntentId;
        final long amountMinor;
        final String currency;
        final String refundId;
        final String paymentId;
        final String orderId;
        String status = "pending";
        String failureReason;
        /** Counts towards the refunded amount of the charge (it succeeded). */
        boolean counted;

        Refund(
                String id,
                String paymentIntentId,
                long amountMinor,
                String currency,
                String refundId,
                String paymentId,
                String orderId) {
            this.id = id;
            this.paymentIntentId = paymentIntentId;
            this.amountMinor = amountMinor;
            this.currency = currency;
            this.refundId = refundId;
            this.paymentId = paymentId;
            this.orderId = orderId;
        }

        RefundView view() {
            return new RefundView(
                    id,
                    paymentIntentId,
                    refundId == null ? null : UUID.fromString(refundId),
                    amountMinor,
                    status,
                    failureReason);
        }

        ObjectNode object(PaymentIntent pi) {
            ObjectNode refund = JSON.createObjectNode();
            refund.put("id", id);
            refund.put("object", "refund");
            refund.put("amount", amountMinor);
            refund.put("currency", currency);
            refund.put("status", status);
            if (failureReason == null) {
                refund.putNull("failure_reason");
            } else {
                refund.put("failure_reason", failureReason);
            }
            refund.put("charge", pi.chargeId);
            refund.put("payment_intent", pi.id);
            refund.put("created", Instant.now().getEpochSecond());
            ObjectNode metadata = refund.putObject("metadata");
            if (refundId != null) {
                metadata.put("refundId", refundId);
            }
            if (paymentId != null) {
                metadata.put("paymentId", paymentId);
            }
            if (orderId != null) {
                metadata.put("orderId", orderId);
            }
            return refund;
        }
    }

    // ============================================================================================ plumbing

    private record StoredResponse(String fingerprint, Reply reply) {}

    /** What the simulator answers to one API call. */
    record Reply(int status, String body, Duration delay) {

        Reply after(Duration newDelay) {
            return new Reply(status, body, newDelay);
        }
    }

    private static Reply ok(JsonNode body) {
        return new Reply(200, JSON.writeValueAsString(body), Duration.ZERO);
    }

    private static Reply error(int status, String type, String code, String message) {
        return error(status, type, code, null, message);
    }

    private static Reply error(int status, String type, String code, String declineCode, String message) {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("type", type);
        if (code != null) {
            error.put("code", code);
        }
        if (declineCode != null) {
            error.put("decline_code", declineCode);
        }
        error.put("message", message);
        return new Reply(status, JSON.writeValueAsString(body), Duration.ZERO);
    }

    private static Map<String, String> form(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            fields.put(
                    URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return fields;
    }

    /** Hands every request to the model; WireMock only provides the HTTP server and the request journal. */
    private static final class SimulatorTransformer implements ResponseDefinitionTransformerV2 {

        static final String NAME = "stripe-simulator";

        private final StripeSimulator simulator;

        SimulatorTransformer(StripeSimulator simulator) {
            this.simulator = simulator;
        }

        @Override
        public ResponseDefinition transform(ServeEvent serveEvent) {
            Reply reply = simulator.handle(serveEvent.getRequest());
            ResponseDefinitionBuilder response = aResponse()
                    .withStatus(reply.status())
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Request-Id", "req_sim_" + simulator.sequence.incrementAndGet())
                    .withBody(reply.body());
            if (!reply.delay().isZero()) {
                response.withFixedDelay((int) reply.delay().toMillis());
            }
            return response.build();
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }
    }
}
