package com.altronixsoft.opp.platform.messaging.consumer;

import com.altronixsoft.opp.platform.messaging.inbox.InboxGuard;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;

/** What a service's listener looks like: parse the envelope, run the business logic once per event id. */
public class TestEventListener {

    public static final String GROUP = "messaging-it-group";

    private final EventEnvelopeReader reader;
    private final InboxGuard inbox;
    private final TestHandler handler;

    public TestEventListener(EventEnvelopeReader reader, InboxGuard inbox, TestHandler handler) {
        this.reader = reader;
        this.inbox = inbox;
        this.handler = handler;
    }

    @KafkaListener(id = "messaging-it-listener", topics = "${test.topic}", groupId = GROUP)
    public void on(ConsumerRecord<String, String> record) {
        handler.remember(headersOf(record));
        reader.read(record).ifPresent(envelope -> {
            inbox.executeOnce(GROUP, envelope.eventId(), () -> handler.handle(envelope));
            handler.afterCommit(envelope.eventId());
        });
    }

    private static Map<String, String> headersOf(ConsumerRecord<?, ?> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }
}
