package com.altronixsoft.opp.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The JSON Schemas are the cross-language contract; they must accept what the code writes and reject violations. */
class EventSchemaTest {

    private static final String SCHEMA_BASE = "https://schemas.altronixsoft.com/opp/events/";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaIdResolvers(resolvers -> resolvers.mapPrefix(SCHEMA_BASE, "classpath:schemas/"))
                    .schemaRegistryConfig(SchemaRegistryConfig.builder()
                            .formatAssertionsEnabled(true)
                            .build()));

    private final EventSerde serde = new EventSerde();

    static Stream<Arguments> events() {
        return EventCatalog.all().stream().map(d -> Arguments.of(d.eventType(), d));
    }

    private static Schema schemaFor(EventDescriptor descriptor) {
        return REGISTRY.getSchema(
                SchemaLocation.of(SCHEMA_BASE + descriptor.eventType() + ".v" + descriptor.eventVersion() + ".json"));
    }

    private static EventDescriptor descriptor(String eventType) {
        return EventCatalog.find(eventType, 1).orElseThrow();
    }

    private ObjectNode sampleJson(EventDescriptor descriptor) {
        DomainEvent payload = Samples.payloads().stream()
                .filter(p -> p.getClass() == descriptor.payloadType())
                .findFirst()
                .orElseThrow();
        return (ObjectNode) JSON.readTree(serde.toJson(Samples.envelope(payload)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void schemaIsAValidDraft2020_12Document(String name, EventDescriptor descriptor) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/" + descriptor.schemaResource())) {
            assertThat(in).as("schema resource %s", descriptor.schemaResource()).isNotNull();
            JsonNode schemaDocument = JSON.readTree(in);
            Schema metaSchema = REGISTRY.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));

            assertThat(metaSchema.validate(schemaDocument)).isEmpty();
            assertThat(schemaDocument.get("$schema").stringValue())
                    .isEqualTo("https://json-schema.org/draft/2020-12/schema");
            assertThat(schemaDocument.get("$id").stringValue())
                    .isEqualTo(SCHEMA_BASE + descriptor.eventType() + ".v" + descriptor.eventVersion() + ".json");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void serializedEventIsValidAgainstItsSchema(String name, EventDescriptor descriptor) {
        List<Error> errors = schemaFor(descriptor).validate(sampleJson(descriptor));

        assertThat(errors).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void eventWithAdditionalPropertiesRemainsValid(String name, EventDescriptor descriptor) {
        ObjectNode json = sampleJson(descriptor);
        json.put("addedByNewerProducer", true);
        ((ObjectNode) json.get("payload")).put("anotherNewField", "x");

        assertThat(schemaFor(descriptor).validate(json)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void payloadFieldsAreExactlyTheSchemaProperties(String name, EventDescriptor descriptor) {
        JsonNode payloadSchema =
                schemaFor(descriptor).getSchemaNode().get("$defs").get("payload");

        assertThat(sampleJson(descriptor).get("payload").propertyNames())
                .containsExactlyInAnyOrderElementsOf(
                        payloadSchema.get("properties").propertyNames());
        assertThat(payloadSchema.get("properties").propertyNames()).containsAll(requiredOf(payloadSchema));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void everyRequiredPayloadFieldIsEnforcedByBothSchemaAndCode(String name, EventDescriptor descriptor) {
        JsonNode payloadSchema =
                schemaFor(descriptor).getSchemaNode().get("$defs").get("payload");
        List<String> required = requiredOf(payloadSchema);
        assertThat(required).isNotEmpty();

        for (String field : required) {
            ObjectNode json = sampleJson(descriptor);
            ((ObjectNode) json.get("payload")).remove(field);

            assertThat(schemaFor(descriptor).validate(json))
                    .as("schema without %s", field)
                    .isNotEmpty();
            assertThatThrownBy(() -> serde.fromJson(json.toString()))
                    .as("code without %s", field)
                    .isInstanceOf(EventSerdeException.class);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void everyRequiredEnvelopeFieldIsEnforced(String name, EventDescriptor descriptor) {
        JsonNode envelopeSchema = REGISTRY.getSchema(SchemaLocation.of(SCHEMA_BASE + "envelope.v1.json"))
                .getSchemaNode();

        for (String field : requiredOf(envelopeSchema)) {
            ObjectNode json = sampleJson(descriptor);
            json.remove(field);

            assertThat(schemaFor(descriptor).validate(json))
                    .as("without %s", field)
                    .isNotEmpty();
        }
        assertThat(requiredOf(envelopeSchema)).doesNotContain("causationId");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("events")
    void eventIdentityFieldsAreBoundToTheEventType(String name, EventDescriptor descriptor) {
        for (String field : List.of("eventType", "eventVersion", "aggregateType", "producer")) {
            ObjectNode json = sampleJson(descriptor);
            if (field.equals("eventVersion")) {
                json.put(field, 2);
            } else {
                json.put(field, "Other");
            }

            assertThat(schemaFor(descriptor).validate(json))
                    .as("wrong %s", field)
                    .isNotEmpty();
        }
    }

    @Test
    void anEventDoesNotValidateAgainstAnotherEventsSchema() {
        List<EventDescriptor> all = EventCatalog.all();
        for (EventDescriptor schemaOwner : all) {
            for (EventDescriptor other : all) {
                if (schemaOwner == other) {
                    continue;
                }
                assertThat(schemaFor(schemaOwner).validate(sampleJson(other)))
                        .as("%s event against %s schema", other.eventType(), schemaOwner.eventType())
                        .isNotEmpty();
            }
        }
    }

    @Test
    void optionalFieldsMayBeAbsent() {
        ObjectNode withoutCausation = sampleJson(descriptor(EventTypes.ORDER_CANCELLED));
        withoutCausation.remove("causationId");
        ObjectNode withoutDecline = sampleJson(descriptor(EventTypes.PAYMENT_ATTEMPT_FAILED));
        ((ObjectNode) withoutDecline.get("payload")).remove("declineCode");

        assertThat(schemaFor(descriptor(EventTypes.ORDER_CANCELLED)).validate(withoutCausation))
                .isEmpty();
        assertThat(schemaFor(descriptor(EventTypes.PAYMENT_ATTEMPT_FAILED)).validate(withoutDecline))
                .isEmpty();
    }

    @Test
    void schemaRejectsInvalidValues() {
        EventDescriptor created = descriptor(EventTypes.ORDER_CREATED);
        Schema schema = schemaFor(created);

        List<Mutation> mutations = new ArrayList<>();
        mutations.add(new Mutation("lower-case currency", json -> payload(json).put("currency", "eur")));
        mutations.add(new Mutation("4-letter currency", json -> payload(json).put("currency", "EURO")));
        mutations.add(new Mutation("zero amount", json -> payload(json).put("amountMinor", 0)));
        mutations.add(new Mutation("fractional amount", json -> payload(json).put("amountMinor", 12.5)));
        mutations.add(new Mutation("string amount", json -> payload(json).put("amountMinor", "100")));
        mutations.add(new Mutation("zero items", json -> payload(json).put("itemCount", 0)));
        mutations.add(new Mutation("non-uuid orderId", json -> payload(json).put("orderId", "order-1")));
        mutations.add(new Mutation("blank customerId", json -> payload(json).put("customerId", "")));
        mutations.add(
                new Mutation("UUIDv4 eventId", json -> json.put("eventId", "3f0c4c0e-9a55-4b57-bf1d-6a5f7d7d2a10")));
        mutations.add(new Mutation("non-UUID correlationId", json -> json.put("correlationId", "abc")));
        mutations.add(new Mutation("epoch occurredAt", json -> json.put("occurredAt", 1760000000)));
        mutations.add(new Mutation("non-ISO occurredAt", json -> json.put("occurredAt", "08/10/2026")));
        mutations.add(new Mutation("unknown producer", json -> json.put("producer", "shipping-service")));
        mutations.add(new Mutation("payload not an object", json -> json.put("payload", "x")));

        for (Mutation mutation : mutations) {
            ObjectNode json = sampleJson(created);
            mutation.apply().accept(json);

            assertThat(schema.validate(json)).as(mutation.name()).isNotEmpty();
        }
    }

    @Test
    void schemaRejectsUnknownEnumValues() {
        ObjectNode cancelled = sampleJson(descriptor(EventTypes.ORDER_CANCELLED));
        ((ObjectNode) cancelled.get("payload")).put("reason", "SOMETHING_NEW");
        ObjectNode refund = sampleJson(descriptor(EventTypes.ORDER_REFUND_REQUESTED));
        ((ObjectNode) refund.get("payload")).put("reason", "OTHER");

        assertThat(schemaFor(descriptor(EventTypes.ORDER_CANCELLED)).validate(cancelled))
                .isNotEmpty();
        assertThat(schemaFor(descriptor(EventTypes.ORDER_REFUND_REQUESTED)).validate(refund))
                .isNotEmpty();
    }

    private static ObjectNode payload(ObjectNode json) {
        return (ObjectNode) json.get("payload");
    }

    private static List<String> requiredOf(JsonNode schemaNode) {
        List<String> required = new ArrayList<>();
        schemaNode.get("required").forEach(n -> required.add(n.stringValue()));
        return required;
    }

    private record Mutation(String name, java.util.function.Consumer<ObjectNode> apply) {}
}
