package com.altronixsoft.opp.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EventSerdeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EventSerde serde = new EventSerde();

    static List<EventEnvelope<? extends DomainEvent>> envelopes() {
        return Samples.envelopes();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("envelopes")
    void roundTripsEveryEvent(EventEnvelope<? extends DomainEvent> original) {
        EventEnvelope<? extends DomainEvent> read = serde.fromJson(serde.toJson(original));

        assertThat(read).isEqualTo(original);
        assertThat(read.payload()).isInstanceOf(original.payload().getClass());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("envelopes")
    void roundTripsThroughBytes(EventEnvelope<? extends DomainEvent> original) {
        assertThat(serde.fromBytes(serde.toBytes(original))).isEqualTo(original);
    }

    @Test
    void writesTheDocumentedEnvelopeFieldsInOrder() throws Exception {
        EventEnvelope<?> envelope = Samples.envelope(new PaymentActionRequired(Samples.PAYMENT_ID, Samples.ORDER_ID));

        JsonNode json = JSON.readTree(serde.toJson(envelope));

        assertThat(json.propertyNames())
                .containsExactly(
                        "eventId",
                        "eventType",
                        "eventVersion",
                        "aggregateType",
                        "aggregateId",
                        "partitionKey",
                        "occurredAt",
                        "producer",
                        "correlationId",
                        "causationId",
                        "payload");
        assertThat(json.get("eventType").stringValue()).isEqualTo("PaymentActionRequired");
        assertThat(json.get("eventVersion").intValue()).isEqualTo(1);
        assertThat(json.get("aggregateType").stringValue()).isEqualTo("Payment");
        assertThat(json.get("aggregateId").stringValue()).isEqualTo(Samples.PAYMENT_ID.toString());
        assertThat(json.get("partitionKey").stringValue()).isEqualTo(Samples.ORDER_ID.toString());
        assertThat(json.get("producer").stringValue()).isEqualTo("payment-service");
    }

    @Test
    void writesTimestampsAsIso8601Strings() throws Exception {
        EventEnvelope<?> envelope = Samples.envelope(
                new PaymentSucceeded(Samples.PAYMENT_ID, Samples.ORDER_ID, 100, "EUR", "pi_1", Samples.NOW));

        JsonNode json = JSON.readTree(serde.toJson(envelope));

        assertThat(json.get("occurredAt").stringValue()).isEqualTo("2026-10-08T12:00:00Z");
        assertThat(json.get("payload").get("succeededAt").stringValue()).isEqualTo("2026-10-08T12:00:00Z");
    }

    @Test
    void omitsAbsentOptionalFields() throws Exception {
        EventEnvelope<?> envelope = EventEnvelope.create(
                new PaymentAttemptFailed(Samples.PAYMENT_ID, Samples.ORDER_ID, "card_declined", null),
                Samples.CORRELATION_ID,
                null,
                Samples.CLOCK);

        JsonNode json = JSON.readTree(serde.toJson(envelope));

        assertThat(json.has("causationId")).isFalse();
        assertThat(json.get("payload").has("declineCode")).isFalse();
        assertThat(serde.fromJson(json.toString())).isEqualTo(envelope);
    }

    @Test
    void doesNotLeakDerivedAccessorsIntoThePayload() throws Exception {
        JsonNode json = JSON.readTree(serde.toJson(Samples.envelope(
                new PaymentSucceeded(Samples.PAYMENT_ID, Samples.ORDER_ID, 100, "EUR", "pi_1", Samples.NOW))));

        assertThat(json.get("payload").propertyNames())
                .containsExactlyInAnyOrder(
                        "paymentId", "orderId", "amountMinor", "currency", "stripePaymentIntentId", "succeededAt");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("envelopes")
    void ignoresUnknownPropertiesInEnvelopeAndPayload(EventEnvelope<? extends DomainEvent> original) throws Exception {
        ObjectNode json = (ObjectNode) JSON.readTree(serde.toJson(original));
        json.put("addedByNewerProducer", "x");
        ((ObjectNode) json.get("payload")).put("anotherNewField", 42);
        ((ObjectNode) json.get("payload")).putObject("nested").put("a", 1);

        assertThat(serde.fromJson(json.toString())).isEqualTo(original);
    }

    @Test
    void unknownEventTypeRaisesTypedExceptionWithTheTypeName() {
        String json = """
                {"eventId":"0199e0a0-6666-7000-8000-000000000006","eventType":"ShipmentDispatched","eventVersion":1,
                 "aggregateType":"Shipment","payload":{"x":1}}""";

        assertThatThrownBy(() -> serde.fromJson(json))
                .isInstanceOfSatisfying(UnknownEventTypeException.class, e -> {
                    assertThat(e.eventType()).isEqualTo("ShipmentDispatched");
                    assertThat(e.eventVersion()).isEqualTo(1);
                    assertThat(e).hasMessageContaining("ShipmentDispatched").hasMessageContaining("version 1");
                })
                .isInstanceOf(EventSerdeException.class);
    }

    @Test
    void unknownVersionOfAKnownTypeIsTreatedAsUnknown() throws Exception {
        ObjectNode json =
                (ObjectNode) JSON.readTree(serde.toJson(Samples.envelopes().getFirst()));
        json.put("eventVersion", 2);

        assertThatThrownBy(() -> serde.fromJson(json.toString()))
                .isInstanceOfSatisfying(UnknownEventTypeException.class, e -> {
                    assertThat(e.eventType()).isEqualTo("OrderCreated");
                    assertThat(e.eventVersion()).isEqualTo(2);
                });
    }

    @Test
    void doesNotHonourTypeHintsFromThePayload() throws Exception {
        EventEnvelope<? extends DomainEvent> original = Samples.envelopes().getFirst();
        ObjectNode json = (ObjectNode) JSON.readTree(serde.toJson(original));
        json.put("@class", "java.lang.ProcessBuilder");
        ((ObjectNode) json.get("payload")).put("@class", "java.lang.ProcessBuilder");

        assertThat(serde.fromJson(json.toString())).isEqualTo(original);
    }

    @Test
    void malformedJsonIsReportedAsSerdeException() {
        assertThatThrownBy(() -> serde.fromJson("{not json"))
                .isExactlyInstanceOf(EventSerdeException.class)
                .hasMessageStartingWith("Malformed event JSON");
        assertThatThrownBy(() -> serde.fromJson("[1,2]"))
                .isExactlyInstanceOf(EventSerdeException.class)
                .hasMessageContaining("JSON object");
        assertThatThrownBy(() -> serde.fromBytes("{} trailing".getBytes(StandardCharsets.UTF_8)))
                .isExactlyInstanceOf(EventSerdeException.class);
    }

    @Test
    void missingEventTypeOrVersionIsReportedAsSerdeException() {
        assertThatThrownBy(() -> serde.fromJson("{\"eventVersion\":1}"))
                .isExactlyInstanceOf(EventSerdeException.class)
                .hasMessageContaining("eventType");
        assertThatThrownBy(() -> serde.fromJson("{\"eventType\":\"OrderCreated\"}"))
                .isExactlyInstanceOf(EventSerdeException.class)
                .hasMessageContaining("eventVersion");
    }

    @Test
    void invalidPayloadValuesAreReportedAsSerdeException() throws Exception {
        ObjectNode json =
                (ObjectNode) JSON.readTree(serde.toJson(Samples.envelopes().getFirst()));
        ((ObjectNode) json.get("payload")).put("currency", "eur");

        assertThatThrownBy(() -> serde.fromJson(json.toString()))
                .isExactlyInstanceOf(EventSerdeException.class)
                .hasMessageContaining("OrderCreated")
                .hasMessageContaining("currency");
    }

    @Test
    void missingRequiredPayloadFieldIsReportedAsSerdeException() throws Exception {
        ObjectNode json =
                (ObjectNode) JSON.readTree(serde.toJson(Samples.envelopes().getFirst()));
        ((ObjectNode) json.get("payload")).remove("orderId");

        assertThatThrownBy(() -> serde.fromJson(json.toString()))
                .isExactlyInstanceOf(EventSerdeException.class)
                .hasMessageContaining("orderId");
    }

    @Test
    void unknownEnumValueIsRejectedNotSilentlyNulled() throws Exception {
        ObjectNode json = (ObjectNode) JSON.readTree(
                serde.toJson(Samples.envelope(new OrderCancelled(Samples.ORDER_ID, CancelReason.CUSTOMER))));
        ((ObjectNode) json.get("payload")).put("reason", "SOMETHING_NEW");

        assertThatThrownBy(() -> serde.fromJson(json.toString())).isExactlyInstanceOf(EventSerdeException.class);
    }

    @Test
    void fractionalAmountIsRejected() throws Exception {
        ObjectNode json =
                (ObjectNode) JSON.readTree(serde.toJson(Samples.envelopes().getFirst()));
        ((ObjectNode) json.get("payload")).put("amountMinor", 49.99);

        assertThatThrownBy(() -> serde.fromJson(json.toString())).isExactlyInstanceOf(EventSerdeException.class);
    }

    @Test
    void acceptsEveryUuidFormatItWrites() {
        UUID id = EventEnvelope.newEventId();

        assertThatCode(() -> serde.fromJson(serde.toJson(Samples.envelope(new PaymentActionRequired(id, id)))))
                .doesNotThrowAnyException();
    }
}
