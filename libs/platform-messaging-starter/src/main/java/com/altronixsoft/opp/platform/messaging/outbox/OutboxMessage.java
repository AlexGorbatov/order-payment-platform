package com.altronixsoft.opp.platform.messaging.outbox;

import java.util.Map;
import java.util.UUID;

/**
 * A claimed outbox row, ready to be sent.
 *
 * @param id the event id
 * @param topic destination topic
 * @param partitionKey Kafka record key
 * @param payload Kafka record value: the event envelope as JSON
 * @param headers Kafka record headers (name to UTF-8 value)
 * @param attempts failed send attempts so far
 */
public record OutboxMessage(
        UUID id, String topic, String partitionKey, String payload, Map<String, String> headers, int attempts) {}
