package com.altronixsoft.opp.platform.messaging.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for the dead-letter container that Spring Kafka starts next to every listener. It only logs coordinates:
 * persistence is the job of the {@code DeadLetterPersister}, and event payloads (customer ids) do not belong in logs.
 */
public class DeadLetterLogger {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterLogger.class);

    /** Invoked by Spring Kafka for each record on a {@code -dlt} topic. */
    public void onDeadLetter(ConsumerRecord<?, ?> record) {
        log.warn(
                "Dead letter on {} partition {} offset {} (key {}); inspect it through /admin/dead-letters",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key());
    }
}
