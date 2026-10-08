package com.altronixsoft.opp.platform.messaging.deadletter;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Stores every record of a {@code *-dlt} topic in {@code dead_letter_message} with status {@code NEW}: payload, key,
 * all headers (including the exception headers Spring Kafka adds), the original topic with the retry suffix removed,
 * and the coordinates of both the dead-letter record and the failing record.
 *
 * <p>Idempotent: a record is identified by its coordinates on the dead-letter topic, so a redelivery after a crash does
 * not create a second row. Counts {@code dlt.messages} (tag {@code topic} = original topic) for every new row.
 */
public class DeadLetterPersister {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPersister.class);

    private static final Pattern RETRY_SUFFIX = Pattern.compile("-retry(-\\d+)?$");
    private static final String DLT_SUFFIX = "-dlt";
    private static final int MAX_HEADER_LENGTH = 8_000;
    private static final int MAX_MESSAGE_LENGTH = 4_000;
    private static final int MAX_KEY_LENGTH = 255;

    /**
     * Headers Spring Kafka encodes as big-endian numbers (4 or 8 bytes, retry timestamps of varying length). Spring Kafka
     * 4 names the failure headers {@code kafka_original-*} / {@code kafka_exception-*}; the older {@code kafka_dlt-*}
     * names are accepted too.
     */
    private static final Set<String> NUMERIC_HEADERS = Set.of(
            KafkaHeaders.ORIGINAL_PARTITION,
            KafkaHeaders.ORIGINAL_OFFSET,
            KafkaHeaders.ORIGINAL_TIMESTAMP,
            KafkaHeaders.DLT_ORIGINAL_PARTITION,
            KafkaHeaders.DLT_ORIGINAL_OFFSET,
            KafkaHeaders.DLT_ORIGINAL_TIMESTAMP,
            "retry_topic-attempts",
            "retry_topic-original-timestamp",
            "retry_topic-backoff-timestamp");

    private final DeadLetterRepository repository;
    private final MeterRegistry meters;

    public DeadLetterPersister(DeadLetterRepository repository, MeterRegistry meters) {
        this.repository = repository;
        this.meters = meters;
    }

    /**
     * @return {@code true} if a new row was stored, {@code false} if the record was already persisted
     */
    public boolean persist(ConsumerRecord<byte[], byte[]> record) {
        Map<String, String> headers = readHeaders(record);
        String originalTopic = originalTopic(
                record.topic(), firstOf(headers, KafkaHeaders.ORIGINAL_TOPIC, KafkaHeaders.DLT_ORIGINAL_TOPIC));
        DeadLetter deadLetter = new DeadLetter(
                null,
                originalTopic,
                record.topic(),
                record.partition(),
                record.offset(),
                intOrNull(firstOf(headers, KafkaHeaders.ORIGINAL_PARTITION, KafkaHeaders.DLT_ORIGINAL_PARTITION)),
                longOrNull(firstOf(headers, KafkaHeaders.ORIGINAL_OFFSET, KafkaHeaders.DLT_ORIGINAL_OFFSET)),
                text(record.key(), MAX_KEY_LENGTH),
                record.value() == null ? new byte[0] : record.value(),
                headers,
                // the cause is the exception the listener threw; the "fqcn" one is Spring's wrapper around it
                truncate(
                        firstOf(
                                headers,
                                KafkaHeaders.EXCEPTION_CAUSE_FQCN,
                                KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
                                KafkaHeaders.EXCEPTION_FQCN,
                                KafkaHeaders.DLT_EXCEPTION_FQCN),
                        255),
                truncate(
                        firstOf(headers, KafkaHeaders.EXCEPTION_MESSAGE, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                        MAX_MESSAGE_LENGTH),
                DeadLetterStatus.NEW,
                null,
                null,
                null);
        boolean inserted = store(deadLetter);
        if (inserted) {
            meters.counter("dlt.messages", "topic", originalTopic).increment();
            log.warn(
                    "Stored dead letter from {} ({}-{}@{}): {}",
                    originalTopic,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    deadLetter.exceptionClass());
        }
        return inserted;
    }

    /**
     * Stores the row. A database that is unavailable makes this throw, and the container retries the same record until
     * it is back. A record whose <i>content</i> the database rejects would block the persister forever, so it is stored
     * again without its headers and with a note, instead of being lost or looping.
     */
    private boolean store(DeadLetter deadLetter) {
        try {
            return repository.insertIfAbsent(deadLetter);
        } catch (NonTransientDataAccessException e) {
            if (e instanceof NonTransientDataAccessResourceException) {
                throw e;
            }
            log.error(
                    "The database rejected the content of dead letter {}-{}@{}; storing it without headers",
                    deadLetter.dltTopic(),
                    deadLetter.partition(),
                    deadLetter.offset(),
                    e);
            Map<String, String> note = Map.of("persister-note", "headers dropped: " + truncate(e.getMessage(), 500));
            return repository.insertIfAbsent(new DeadLetter(
                    null,
                    deadLetter.originalTopic(),
                    deadLetter.dltTopic(),
                    deadLetter.partition(),
                    deadLetter.offset(),
                    deadLetter.originalPartition(),
                    deadLetter.originalOffset(),
                    deadLetter.messageKey(),
                    deadLetter.payload(),
                    note,
                    deadLetter.exceptionClass(),
                    deadLetter.exceptionMessage(),
                    DeadLetterStatus.NEW,
                    null,
                    null,
                    null));
        }
    }

    /** The source topic: the header value without a {@code -retry-N} suffix, or the DLT name without {@code -dlt}. */
    static String originalTopic(String dltTopic, String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            return RETRY_SUFFIX.matcher(headerValue).replaceFirst("");
        }
        return dltTopic.endsWith(DLT_SUFFIX)
                ? dltTopic.substring(0, dltTopic.length() - DLT_SUFFIX.length())
                : dltTopic;
    }

    private static Map<String, String> readHeaders(ConsumerRecord<?, ?> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(truncate(header.key(), MAX_KEY_LENGTH), headerText(header));
        }
        return headers;
    }

    private static String headerText(Header header) {
        byte[] value = header.value();
        if (value == null) {
            return "";
        }
        if (NUMERIC_HEADERS.contains(header.key()) && value.length > 0 && value.length <= Long.BYTES) {
            return new BigInteger(1, value).toString();
        }
        return text(value, MAX_HEADER_LENGTH);
    }

    private static String firstOf(Map<String, String> headers, String... names) {
        for (String name : names) {
            String value = headers.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * UTF-8 text that PostgreSQL accepts: the NUL character cannot be stored in {@code text} or {@code jsonb}, and a
     * poison message may contain anything.
     */
    private static String text(byte[] bytes, int maxLength) {
        if (bytes == null) {
            return null;
        }
        return truncate(new String(bytes, StandardCharsets.UTF_8).replace('\u0000', '�'), maxLength);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String clean = value.replace('\u0000', '�');
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private static Integer intOrNull(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOrNull(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
