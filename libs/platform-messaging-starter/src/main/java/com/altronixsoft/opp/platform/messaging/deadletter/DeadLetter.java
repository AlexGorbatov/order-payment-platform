package com.altronixsoft.opp.platform.messaging.deadletter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A row of {@code dead_letter_message}.
 *
 * @param partition partition of the record on the dead-letter topic
 * @param offset offset of the record on the dead-letter topic
 * @param originalPartition partition of the failing record as reported by Spring Kafka; may be {@code null}
 * @param originalOffset offset of the failing record as reported by Spring Kafka; may be {@code null}
 * @param payload the record value exactly as received
 * @param headers all record headers as text (exception headers included)
 */
public record DeadLetter(
        UUID id,
        String originalTopic,
        String dltTopic,
        int partition,
        long offset,
        Integer originalPartition,
        Long originalOffset,
        String messageKey,
        byte[] payload,
        Map<String, String> headers,
        String exceptionClass,
        String exceptionMessage,
        DeadLetterStatus status,
        String note,
        Instant createdAt,
        Instant updatedAt) {}
