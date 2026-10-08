package com.altronixsoft.opp.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Envelope ↔ JSON.
 *
 * <ul>
 *   <li>Timestamps are ISO-8601 strings, ids are canonical UUID strings, absent optional fields are omitted.
 *   <li>Unknown properties are ignored when reading, so an additive change by a newer producer does not break
 *       consumers (forward compatibility).
 *   <li>No polymorphic/default typing: the payload class is chosen from {@code eventType} + {@code eventVersion} via
 *       {@link EventCatalog}; an unknown pair raises {@link UnknownEventTypeException}.
 * </ul>
 *
 * Instances are immutable and thread-safe.
 */
public final class EventSerde {

    private final JsonMapper mapper;

    public EventSerde() {
        this(newMapper());
    }

    /** Uses a caller-provided mapper; it must be configured like {@link #newMapper()}. */
    public EventSerde(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** The mapper configuration the wire format relies on. */
    public static JsonMapper newMapper() {
        return JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
    }

    public String toJson(EventEnvelope<?> envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JacksonException e) {
            throw new EventSerdeException("Cannot serialize " + envelope.eventType() + " " + envelope.eventId(), e);
        }
    }

    public byte[] toBytes(EventEnvelope<?> envelope) {
        return toJson(envelope).getBytes(StandardCharsets.UTF_8);
    }

    public EventEnvelope<? extends DomainEvent> fromBytes(byte[] json) {
        return fromJson(new String(json, StandardCharsets.UTF_8));
    }

    /**
     * Parses an envelope.
     *
     * @throws UnknownEventTypeException the type or version is not registered (callers skip such events)
     * @throws EventSerdeException the JSON is malformed or violates the contract
     */
    public EventEnvelope<? extends DomainEvent> fromJson(String json) {
        JsonNode root = readTree(json);
        if (!root.isObject()) {
            throw new EventSerdeException("Event envelope must be a JSON object");
        }
        String eventType = text(root, "eventType");
        int eventVersion = integer(root, "eventVersion");
        EventDescriptor descriptor = EventCatalog.find(eventType, eventVersion)
                .orElseThrow(() -> new UnknownEventTypeException(eventType, eventVersion));
        try {
            JavaType type =
                    mapper.getTypeFactory().constructParametricType(EventEnvelope.class, descriptor.payloadType());
            return mapper.treeToValue(root, type);
        } catch (JacksonException e) {
            throw new EventSerdeException(
                    "Invalid " + eventType + " v" + eventVersion + " envelope: " + e.getOriginalMessage(), e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JacksonException e) {
            throw new EventSerdeException("Malformed event JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString()) {
            throw new EventSerdeException("Event envelope is missing string field '" + field + "'");
        }
        return node.stringValue();
    }

    private static int integer(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isInt()) {
            throw new EventSerdeException("Event envelope is missing integer field '" + field + "'");
        }
        return node.intValue();
    }
}
