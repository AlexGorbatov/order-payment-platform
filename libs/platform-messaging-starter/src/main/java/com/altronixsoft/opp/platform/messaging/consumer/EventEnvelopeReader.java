package com.altronixsoft.opp.platform.messaging.consumer;

import com.altronixsoft.opp.contracts.DomainEvent;
import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.EventSerde;
import com.altronixsoft.opp.contracts.EventSerdeException;
import com.altronixsoft.opp.contracts.UnknownEventTypeException;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the record value of a listener into an {@link EventEnvelope}.
 *
 * <ul>
 *   <li>Unknown event types and versions yield an empty result and an INFO log: the record is acknowledged and skipped
 *       (forward compatibility, architecture §9.3), not dead-lettered.
 *   <li>Malformed JSON or a contract violation throws {@link EventSerdeException}; it is classified as non-retryable,
 *       so the record goes to the DLT without retries (F15).
 * </ul>
 *
 * Listeners receive the raw JSON ({@code String}) on purpose: the dead-letter record keeps the exact bytes that were
 * received, including fields this consumer does not know yet, so a later replay loses nothing.
 */
public class EventEnvelopeReader {

    private static final Logger log = LoggerFactory.getLogger(EventEnvelopeReader.class);

    private final EventSerde serde;

    public EventEnvelopeReader(EventSerde serde) {
        this.serde = serde;
    }

    /**
     * @return the envelope, or empty when the record is to be skipped (unknown type or version, or no value)
     * @throws EventSerdeException the value is not a valid event envelope
     */
    public Optional<EventEnvelope<? extends DomainEvent>> read(ConsumerRecord<?, String> record) {
        if (record.value() == null) {
            log.warn("Skipping record without value on {}-{}@{}", record.topic(), record.partition(), record.offset());
            return Optional.empty();
        }
        try {
            return Optional.of(serde.fromJson(record.value()));
        } catch (UnknownEventTypeException e) {
            log.info(
                    "Skipping event of unknown type '{}' version {} on {}-{}@{}",
                    e.eventType(),
                    e.eventVersion(),
                    record.topic(),
                    record.partition(),
                    record.offset());
            return Optional.empty();
        }
    }
}
