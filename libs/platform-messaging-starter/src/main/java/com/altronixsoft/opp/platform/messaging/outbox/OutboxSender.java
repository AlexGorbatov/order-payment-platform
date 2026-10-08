package com.altronixsoft.opp.platform.messaging.outbox;

/** Delivers one claimed outbox row to the broker and returns only after the broker acknowledged it. */
@FunctionalInterface
public interface OutboxSender {

    /**
     * @throws OutboxSendException when the record was not acknowledged; the relay then stops the batch
     */
    void send(OutboxMessage message);
}
