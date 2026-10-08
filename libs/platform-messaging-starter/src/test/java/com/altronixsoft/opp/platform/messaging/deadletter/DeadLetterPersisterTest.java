package com.altronixsoft.opp.platform.messaging.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.kafka.support.KafkaHeaders;

class DeadLetterPersisterTest {

    private final DeadLetterRepository repository = mock(DeadLetterRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeadLetterPersister persister = new DeadLetterPersister(repository, meters);

    @BeforeEach
    void inserts() {
        when(repository.insertIfAbsent(any())).thenReturn(true);
    }

    private static ConsumerRecord<byte[], byte[]> record(String topic, byte[] key, byte[] value) {
        return new ConsumerRecord<>(topic, 2, 17L, key, value);
    }

    private DeadLetter stored() {
        ArgumentCaptor<DeadLetter> captor = ArgumentCaptor.forClass(DeadLetter.class);
        verify(repository).insertIfAbsent(captor.capture());
        return captor.getValue();
    }

    @Test
    void storesPayloadKeyAndDltCoordinates() {
        persister.persist(record("orders.events.v1-dlt", "k1".getBytes(), "{\"a\":1}".getBytes()));

        DeadLetter stored = stored();
        assertThat(stored.originalTopic()).isEqualTo("orders.events.v1");
        assertThat(stored.dltTopic()).isEqualTo("orders.events.v1-dlt");
        assertThat(stored.partition()).isEqualTo(2);
        assertThat(stored.offset()).isEqualTo(17L);
        assertThat(stored.messageKey()).isEqualTo("k1");
        assertThat(stored.payload()).isEqualTo("{\"a\":1}".getBytes());
        assertThat(stored.status()).isEqualTo(DeadLetterStatus.NEW);
        assertThat(meters.get("dlt.messages")
                        .tag("topic", "orders.events.v1")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void readsSpringKafkaFailureHeadersAndPrefersTheCauseOfTheListenerException() {
        ConsumerRecord<byte[], byte[]> record = record("orders.events.v1-dlt", null, "x".getBytes());
        record.headers()
                .add(KafkaHeaders.ORIGINAL_TOPIC, "orders.events.v1-retry-2".getBytes())
                .add(
                        KafkaHeaders.ORIGINAL_PARTITION,
                        ByteBuffer.allocate(4).putInt(1).array())
                .add(
                        KafkaHeaders.ORIGINAL_OFFSET,
                        ByteBuffer.allocate(8).putLong(99L).array())
                .add(
                        KafkaHeaders.EXCEPTION_FQCN,
                        "org.springframework.kafka.listener.ListenerExecutionFailedException".getBytes())
                .add(KafkaHeaders.EXCEPTION_CAUSE_FQCN, "java.lang.IllegalStateException".getBytes())
                .add(KafkaHeaders.EXCEPTION_MESSAGE, "Listener failed; boom".getBytes())
                .add("retry_topic-attempts", ByteBuffer.allocate(4).putInt(4).array())
                .add("retry_topic-backoff-timestamp", new byte[] {1, 2, 3, 4, 5, 6});

        persister.persist(record);

        DeadLetter stored = stored();
        assertThat(stored.originalTopic()).as("retry suffix is stripped").isEqualTo("orders.events.v1");
        assertThat(stored.originalPartition()).isEqualTo(1);
        assertThat(stored.originalOffset()).isEqualTo(99L);
        assertThat(stored.exceptionClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(stored.exceptionMessage()).isEqualTo("Listener failed; boom");
        assertThat(stored.headers())
                .containsEntry("retry_topic-attempts", "4")
                .containsEntry("retry_topic-backoff-timestamp", "1108152157446");
        assertThat(stored.messageKey()).isNull();
    }

    @Test
    void fallsBackToTheWrapperExceptionAndToTheDltNameWhenHeadersAreMissing() {
        ConsumerRecord<byte[], byte[]> record = record("payments.events.v1-dlt", null, null);
        record.headers().add(KafkaHeaders.EXCEPTION_FQCN, "x.Wrapper".getBytes());

        persister.persist(record);

        DeadLetter stored = stored();
        assertThat(stored.originalTopic()).isEqualTo("payments.events.v1");
        assertThat(stored.exceptionClass()).isEqualTo("x.Wrapper");
        assertThat(stored.payload()).isEmpty();
        assertThat(stored.originalPartition()).isNull();
    }

    @Test
    void understandsTheOlderDltHeaderNames() {
        ConsumerRecord<byte[], byte[]> record = record("a-dlt", "k".getBytes(), "v".getBytes());
        record.headers()
                .add(KafkaHeaders.DLT_ORIGINAL_TOPIC, "a".getBytes())
                .add(
                        KafkaHeaders.DLT_ORIGINAL_PARTITION,
                        ByteBuffer.allocate(4).putInt(7).array())
                .add(KafkaHeaders.DLT_EXCEPTION_FQCN, "x.Old".getBytes());

        persister.persist(record);

        DeadLetter stored = stored();
        assertThat(stored.originalTopic()).isEqualTo("a");
        assertThat(stored.originalPartition()).isEqualTo(7);
        assertThat(stored.exceptionClass()).isEqualTo("x.Old");
    }

    @Test
    void replacesNulCharactersEverywherePostgresqlWouldRejectThem() {
        ConsumerRecord<byte[], byte[]> record =
                record("a-dlt", "k\u0000ey".getBytes(StandardCharsets.UTF_8), "v".getBytes());
        record.headers().add("na\u0000me", "va\u0000lue".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.EXCEPTION_MESSAGE, "bad \u0000 input".getBytes(StandardCharsets.UTF_8));

        persister.persist(record);

        DeadLetter stored = stored();
        assertThat(stored.messageKey()).isEqualTo("k�ey");
        assertThat(stored.headers()).containsEntry("na�me", "va�lue");
        assertThat(stored.exceptionMessage()).isEqualTo("bad � input");
    }

    @Test
    void truncatesVeryLongValues() {
        ConsumerRecord<byte[], byte[]> record = record("a-dlt", "k".repeat(1000).getBytes(), "v".getBytes());
        record.headers().add(KafkaHeaders.EXCEPTION_MESSAGE, "m".repeat(50_000).getBytes());
        record.headers()
                .add(KafkaHeaders.EXCEPTION_STACKTRACE, "s".repeat(50_000).getBytes());

        persister.persist(record);

        DeadLetter stored = stored();
        assertThat(stored.messageKey()).hasSize(255);
        assertThat(stored.exceptionMessage()).hasSize(4_000);
        assertThat(stored.headers().get(KafkaHeaders.EXCEPTION_STACKTRACE)).hasSize(8_000);
    }

    @Test
    void aRecordThatIsAlreadyStoredIsNotCountedAgain() {
        when(repository.insertIfAbsent(any())).thenReturn(false);

        boolean inserted = persister.persist(record("a-dlt", null, "v".getBytes()));

        assertThat(inserted).isFalse();
        assertThat(meters.find("dlt.messages").counter()).isNull();
    }

    @Test
    void contentTheDatabaseRejectsIsStoredWithoutHeadersInsteadOfBlockingThePersister() {
        when(repository.insertIfAbsent(any()))
                .thenThrow(new DataIntegrityViolationException("unsupported Unicode escape sequence"))
                .thenReturn(true);
        ConsumerRecord<byte[], byte[]> record = record("a-dlt", "k".getBytes(), "payload".getBytes());
        record.headers().add("h", "v".getBytes());

        boolean inserted = persister.persist(record);

        assertThat(inserted).isTrue();
        ArgumentCaptor<DeadLetter> captor = ArgumentCaptor.forClass(DeadLetter.class);
        verify(repository, times(2)).insertIfAbsent(captor.capture());
        DeadLetter fallback = captor.getAllValues().get(1);
        assertThat(fallback.payload()).isEqualTo("payload".getBytes());
        assertThat(fallback.headers()).containsOnlyKeys("persister-note");
        assertThat(fallback.headers().get("persister-note")).contains("headers dropped");
    }

    @Test
    void aDatabaseOutageIsNotSwallowedSoTheRecordIsRetried() {
        when(repository.insertIfAbsent(any())).thenThrow(new NonTransientDataAccessResourceException("down"));

        assertThatThrownBy(() -> persister.persist(record("a-dlt", null, "v".getBytes())))
                .isInstanceOf(NonTransientDataAccessResourceException.class);
        verify(repository, times(1)).insertIfAbsent(any());
    }

    @Test
    void originalTopicStripsOnlyRetrySuffixes() {
        assertThat(DeadLetterPersister.originalTopic("x-dlt", "orders.events.v1-retry-0"))
                .isEqualTo("orders.events.v1");
        assertThat(DeadLetterPersister.originalTopic("x-dlt", "orders.events.v1-retry-12"))
                .isEqualTo("orders.events.v1");
        assertThat(DeadLetterPersister.originalTopic("x-dlt", "orders.events.v1-retry"))
                .isEqualTo("orders.events.v1");
        assertThat(DeadLetterPersister.originalTopic("x-dlt", "my-retry-topic.v1"))
                .isEqualTo("my-retry-topic.v1");
        assertThat(DeadLetterPersister.originalTopic("orders-dlt", "")).isEqualTo("orders");
        assertThat(DeadLetterPersister.originalTopic("weird", null)).isEqualTo("weird");
    }
}
